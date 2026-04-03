package com.thy.logviewer.service;

import com.thy.logviewer.config.LogWebSocketHandler;
import com.thy.logviewer.config.LogViewerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thy.logviewer.model.CursorPageResponse;
import com.thy.logviewer.model.LogEntry;
import com.thy.logviewer.model.PageResponse;
import com.thy.logviewer.model.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.socket.WebSocketSession;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.annotation.PostConstruct;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);

    private final LogWebSocketHandler webSocketHandler;
    private final LogViewerProperties properties;

    private final Map<WebSocketSession, Thread> streamingThreads = new ConcurrentHashMap<>();

    /** WebSocket 正向合并时，跨轮询暂存未闭合的多行日志（避免先发头行、再发整段导致重复） */
    private final ConcurrentHashMap<String, List<String>> wsPendingTailLines = new ConcurrentHashMap<>();
    /** 按字节 tail 时，上一轮末尾未遇换行符的残片（与 {@link #wsPendingTailLines} 不同：这是物理行切分） */
    private final ConcurrentHashMap<String, String> wsTailBytePartial = new ConcurrentHashMap<>();
    private static final long MAX_LOG_TAIL_DELTA_BYTES = 16L * 1024 * 1024;

    // Logback 典型格式：%d [%thread] %-5level %logger(%L) - %msg%n
    // 必须用「logger 末尾的 (行号) - 」切分，避免 logger 中含 `) - `（如 DispatcherServlet(525) - ...）时被 (.+?)\s+-\s+ 误切到第一个 ) - 导致整行匹配失败，进而被当成堆栈续行错绑到上一条。
    private static final Pattern LOG_PATTERN_LOGBACK_LINE = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[([^\\]]+)\\] (\\w+) (.+\\(\\d+\\)) - (.+)"
    );
    // 兜底：无行号或非常规格式
    private static final Pattern LOG_PATTERN_HYPHEN = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[([^\\]]+)\\] (\\w+) (.+?)\\s+-\\s+(.+)"
    );
    /**
     * 行首为 logback 标准时间（trim 后）；不要求紧跟 {@code [}，避免 BOM/空格或格式微差时仍被当成续行，与整段日志糊成一条。
     */
    private static final Pattern LINE_LOOKS_LIKE_LOG_HEADER = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\b"
    );
    /** 行首时间（与 logback %d{yyyy-MM-dd HH:mm:ss.SSS} 一致），用于合并排序，避免仅依赖已解析字段导致顺序与肉眼所见不一致 */
    private static final Pattern LEADING_LOG_TIMESTAMP = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})"
    );
    private static final DateTimeFormatter SORT_TS_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    // 例子（较旧的输出样式）：
    // 2026-04-02 10:20:30.123 [main] INFO com.xxx.Class: hello
    private static final Pattern LOG_PATTERN_COLON = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[([^\\]]+)\\] (\\w+) ([^:]+):\\s*(.+)"
    );

    public LogService(@Lazy LogWebSocketHandler webSocketHandler, LogViewerProperties properties) {
        this.webSocketHandler = webSocketHandler;
        this.properties = properties;
    }

    // 多服务器相关逻辑已移除，无需初始化

    private String getLogPath() {
        if (properties.isAutoDetectLogback()) {
            String detectedPath = parseLogbackConfig();
            if (detectedPath != null) {
                return detectedPath;
            }
        }
        return properties.getLogPath();
    }

    private String parseLogbackConfig() {
        try {
            ClassPathResource resource = new ClassPathResource("logback-spring.xml");
            if (!resource.exists()) {
                return null;
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(resource.getInputStream()));

            // 通用兜底：返回找到的第一个 <File>/<file> 路径。
            String[] tagNames = new String[]{"File", "file"};
            for (String tag : tagNames) {
                NodeList fileNodes = doc.getElementsByTagName(tag);
                for (int i = 0; i < fileNodes.getLength(); i++) {
                    Element fileElement = (Element) fileNodes.item(i);
                    String filePath = fileElement.getTextContent();
                    if (filePath != null && !filePath.trim().isEmpty()) {
                        return filePath.trim();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse logback-spring.xml", e);
        }
        return null;
    }

    // 多服务器相关方法已移除

    private String getLogPath(String type) {
        // 通过 logback-spring.xml 的 appender name 自动探测日志文件路径。
        if (properties.isAutoDetectLogback()) {
            if ("error".equalsIgnoreCase(type)) {
                String detected = parseLogbackAppenderFilePath("errorLog");
                if (detected != null) return detected;
            }
            if ("info".equalsIgnoreCase(type)) {
                String detected = parseLogbackAppenderFilePath("infoLog");
                if (detected != null) return detected;
            }
        }
        return getLogPath(); // 兼容老逻辑
    }

    private String parseLogbackAppenderFilePath(String appenderName) {
        try {
            ClassPathResource resource = new ClassPathResource("logback-spring.xml");
            if (!resource.exists()) {
                return null;
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(resource.getInputStream()));

            // 解析简单的 ${...} 占位符：来自 <property name="..." value="..."/>
            Map<String, String> properties = new HashMap<>();
            NodeList propertyNodes = doc.getElementsByTagName("property");
            for (int p = 0; p < propertyNodes.getLength(); p++) {
                Element propEl = (Element) propertyNodes.item(p);
                String name = propEl.getAttribute("name");
                String value = propEl.getAttribute("value");
                if (name != null && !name.trim().isEmpty() && value != null) {
                    properties.put(name.trim(), value.trim());
                }
            }

            NodeList appenderNodes = doc.getElementsByTagName("appender");
            for (int i = 0; i < appenderNodes.getLength(); i++) {
                Element appenderElement = (Element) appenderNodes.item(i);
                String name = appenderElement.getAttribute("name");
                if (name != null && name.equals(appenderName)) {
                    // 优先使用 <File>（大写 F），否则兜底使用 <file>。
                    for (String tag : new String[]{"File", "file"}) {
                        NodeList fileNodes = appenderElement.getElementsByTagName(tag);
                        for (int j = 0; j < fileNodes.getLength(); j++) {
                            Element fileElement = (Element) fileNodes.item(j);
                            String filePath = fileElement.getTextContent();
                            if (filePath != null && !filePath.trim().isEmpty()) {
                                String resolved = filePath.trim();
                                for (Map.Entry<String, String> entry : properties.entrySet()) {
                                    resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
                                }
                                return resolved;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse logback appender file path: " + appenderName, e);
        }
        return null;
    }

    public PageResponse<LogEntry> getLogs(String keyword, String level, String thread, String logger, String startTime, String endTime, int page, int size, String type) {
        try {
            List<String> lines = readLogFile(getLogPath(type));
            List<LogEntry> entries = parseLogLines(lines);

            // 过滤
            if (keyword != null && !keyword.isEmpty()) {
                entries = entries.stream()
                    .filter(e -> e.getMessage().contains(keyword))
                    .collect(Collectors.toList());
            }
            if (level != null && !level.isEmpty()) {
                entries = entries.stream()
                    .filter(e -> level.equalsIgnoreCase(e.getLevel()))
                    .collect(Collectors.toList());
            }
            if (thread != null && !thread.isEmpty()) {
                entries = entries.stream()
                    .filter(e -> e.getThread() != null && e.getThread().contains(thread))
                    .collect(Collectors.toList());
            }
            if (logger != null && !logger.isEmpty()) {
                entries = entries.stream()
                    .filter(e -> e.getLogger() != null && e.getLogger().contains(logger))
                    .collect(Collectors.toList());
            }
            if (startTime != null && !startTime.isEmpty()) {
                LocalDateTime start = LocalDateTime.parse(startTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                entries = entries.stream()
                    .filter(e -> e.getTimestamp() != null && !e.getTimestamp().isBefore(start))
                    .collect(Collectors.toList());
            }
            if (endTime != null && !endTime.isEmpty()) {
                LocalDateTime end = LocalDateTime.parse(endTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                entries = entries.stream()
                    .filter(e -> e.getTimestamp() != null && !e.getTimestamp().isAfter(end))
                    .collect(Collectors.toList());
            }

            // 按时间戳倒序
            entries.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));

            int totalElements = entries.size();

            // 分页
            int startIdx = page * size;
            int endIdx = Math.min(startIdx + size, entries.size());
            List<LogEntry> pageContent = entries.subList(startIdx, endIdx);

            return new PageResponse<>(pageContent, totalElements, page, size);
        } catch (Exception e) {
            log.error("Error reading logs", e);
            return new PageResponse<>(Collections.emptyList(), 0, page, size);
        }
    }

    public CursorPageResponse<LogEntry> getLogsCursor(String keyword,
                                                        String level,
                                                        String thread,
                                                        String logger,
                                                        String archiveDate,
                                                        String cursor,
                                                        int size) {
        try {
            if (size <= 0) {
                size = 100;
            }

            String normalizedArchiveDate = normalizeArchiveDate(archiveDate);
            boolean archiveMode = normalizedArchiveDate != null;
            LocalDate archiveDay = null;
            if (archiveMode) {
                archiveDay = LocalDate.parse(normalizedArchiveDate, DateTimeFormatter.ISO_LOCAL_DATE);
            }

            String mode = resolveStreamMode(level); // 目标模式：info / error / combined
            long snapshotInfoSize = 0L;
            long snapshotErrorSize = 0L;
            String filtersHash = computeFiltersHash(mode, keyword, level, thread, logger, normalizedArchiveDate);

            CursorState state;
            if (cursor == null || cursor.trim().isEmpty()) {
                if (!archiveMode) {
                    snapshotInfoSize = snapshotSize("info");
                    snapshotErrorSize = snapshotSize("error");
                }
                state = new CursorState(1, filtersHash, mode, normalizedArchiveDate, 0, snapshotInfoSize, snapshotErrorSize, System.currentTimeMillis());
            } else {
                state = decodeCursor(cursor);
                boolean needFreshSnapshot = state == null
                    || state.v != 1
                    || state.filtersHash == null
                    || !state.filtersHash.equals(filtersHash)
                    || !mode.equals(state.mode)
                    || !Objects.equals(state.archiveDate, normalizedArchiveDate);

                if (!needFreshSnapshot && !archiveMode) {
                    // 如果活跃文件变小（滚动/截断），旧快照就不再有效。
                    if ("info".equals(mode) || "combined".equals(mode)) {
                        needFreshSnapshot = snapshotSize("info") < state.snapshotInfoSize;
                    }
                    if (!needFreshSnapshot && ("error".equals(mode) || "combined".equals(mode))) {
                        needFreshSnapshot = snapshotSize("error") < state.snapshotErrorSize;
                    }
                }

                if (needFreshSnapshot) {
                    if (!archiveMode) {
                        snapshotInfoSize = snapshotSize("info");
                        snapshotErrorSize = snapshotSize("error");
                    }
                    state = new CursorState(1, filtersHash, mode, normalizedArchiveDate, 0, snapshotInfoSize, snapshotErrorSize, System.currentTimeMillis());
                }
            }

            int skip = Math.max(0, state.skip);
            snapshotInfoSize = state.snapshotInfoSize;
            snapshotErrorSize = state.snapshotErrorSize;

            int targetCount = skip + size + 1; // 用于判断是否还有下一页
            int fetchCount = targetCount + size; // 为时间戳相同的情况留一点缓冲

            List<LogEntry> infoMatches = Collections.emptyList();
            List<LogEntry> errorMatches = Collections.emptyList();

            if ("info".equals(mode) || "combined".equals(mode)) {
                infoMatches = fetchTopMatchesForStream("info", keyword, level, thread, logger, archiveDay, snapshotInfoSize, archiveMode, fetchCount);
            }
            if ("error".equals(mode) || "combined".equals(mode)) {
                errorMatches = fetchTopMatchesForStream("error", keyword, level, thread, logger, archiveDay, snapshotErrorSize, archiveMode, fetchCount);
            }

            List<LogEntry> merged = new ArrayList<>(infoMatches.size() + errorMatches.size());
            merged.addAll(infoMatches);
            merged.addAll(errorMatches);
            // info / error 合并为一条时间线后，统一按行首时间倒序（越新越前）
            sortMergedEntriesByTimeDescending(merged);
            merged = dedupeByRawLine(merged);
            sortMergedEntriesByTimeDescending(merged);

            int from = Math.min(skip, merged.size());
            int to = Math.min(from + size, merged.size());
            List<LogEntry> page = from >= to ? Collections.emptyList() : merged.subList(from, to);

            boolean hasNext = merged.size() >= targetCount;
            String nextCursor = null;
            if (hasNext) {
                nextCursor = encodeCursor(new CursorState(1, filtersHash, mode, normalizedArchiveDate, skip + size, snapshotInfoSize, snapshotErrorSize, state.snapshotMillis));
            }

            return new CursorPageResponse<>(page, hasNext, nextCursor, size);
        } catch (Exception e) {
            log.error("Error reading logs(cursor)", e);
            return new CursorPageResponse<>(Collections.emptyList(), false, null, size);
        }
    }

    /** 合并后的列表按「行首可见时间」倒序，供 REST 返回与游标分页切片 */
    private void sortMergedEntriesByTimeDescending(List<LogEntry> merged) {
        if (merged == null || merged.size() <= 1) {
            return;
        }
        merged.sort(this::compareForStableOrdering);
    }

    private int compareForStableOrdering(LogEntry a, LogEntry b) {
        if (a == b) return 0;
        LocalDateTime ta = extractSortTimestamp(a);
        LocalDateTime tb = extractSortTimestamp(b);
        if (ta == null && tb == null) {
            return safeCompareRawLineDesc(a, b);
        }
        if (ta == null) return 1; // 无行首时间的放在最后
        if (tb == null) return -1;
        int c = tb.compareTo(ta); // 倒序：越新越靠前
        if (c != 0) return c;
        // 同一毫秒内：按整行字典序倒序，更接近「文件里靠后的行更晚写出」的常见情况
        return safeCompareRawLineDesc(a, b);
    }

    /** 优先用首行可见时间排序，与界面行首字符串一致，避免 timestamp 字段缺失/不一致时顺序错乱 */
    private LocalDateTime extractSortTimestamp(LogEntry e) {
        if (e == null) {
            return null;
        }
        String raw = e.getRawLine() != null ? e.getRawLine() : e.getMessage();
        if (raw != null && !raw.isEmpty()) {
            int nl = raw.indexOf('\n');
            String first = (nl >= 0 ? raw.substring(0, nl) : raw).trim();
            Matcher m = LEADING_LOG_TIMESTAMP.matcher(first);
            if (m.find()) {
                try {
                    return LocalDateTime.parse(m.group(1), SORT_TS_FORMAT);
                } catch (Exception ignored) {
                }
            }
        }
        return e.getTimestamp();
    }

    private int safeCompareRawLineDesc(LogEntry a, LogEntry b) {
        String ra = a.getRawLine() != null ? a.getRawLine() : a.getMessage();
        String rb = b.getRawLine() != null ? b.getRawLine() : b.getMessage();
        if (ra == null && rb == null) return 0;
        if (ra == null) return 1;
        if (rb == null) return -1;
        return rb.compareTo(ra);
    }

    /**
     * 合并 info/error 两条流后，按完整行内容去重（避免 combined 模式或配置异常时出现两条相同记录）。
     */
    private List<LogEntry> dedupeByRawLine(List<LogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return entries;
        }
        Set<String> seen = new HashSet<>();
        List<LogEntry> out = new ArrayList<>(entries.size());
        for (LogEntry e : entries) {
            String key = e.getRawLine() != null ? e.getRawLine() : e.getMessage();
            if (key == null || key.isEmpty()) {
                out.add(e);
                continue;
            }
            if (seen.add(key)) {
                out.add(e);
            }
        }
        return out;
    }

    private String resolveStreamMode(String level) {
        if (level == null || level.trim().isEmpty()) {
            return "combined";
        }
        if ("error".equalsIgnoreCase(level) || "ERROR".equalsIgnoreCase(level)) {
            return "error";
        }
        return "info";
    }

    private String normalizeArchiveDate(String archiveDate) {
        if (archiveDate == null) return null;
        String s = archiveDate.trim();
        if (s.isEmpty()) return null;
        return s;
    }

    private String computeFiltersHash(String mode,
                                       String keyword,
                                       String level,
                                       String thread,
                                       String logger,
                                       String archiveDate) throws Exception {
        String raw = String.valueOf(mode) + "|" + String.valueOf(keyword) + "|" + String.valueOf(level) + "|" +
            String.valueOf(thread) + "|" + String.valueOf(logger) + "|" + String.valueOf(archiveDate);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private LocalDateTime parseDateTimeMaybe(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;

        List<DateTimeFormatter> fmts = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        );
        for (DateTimeFormatter fmt : fmts) {
            try {
                return LocalDateTime.parse(s, fmt);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private long snapshotSize(String type) {
        try {
            Path p = Paths.get(getLogPath(type));
            if (!Files.exists(p)) return 0L;
            return Files.size(p);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 同时用于游标分页与 WebSocket JSON；必须注册 JavaTime，否则 {@link LogEntry#getTimestamp()} 序列化失败且推送被静默吞掉 */
    private static final ObjectMapper CURSOR_MAPPER = createCursorObjectMapper();

    private static ObjectMapper createCursorObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    private static class CursorState {
        public int v;
        public String filtersHash;
        public String mode;
        public String archiveDate;
        public int skip;
        public long snapshotInfoSize;
        public long snapshotErrorSize;
        public long snapshotMillis;

        // Jackson 需要无参构造
        public CursorState() {}

        public CursorState(int v,
                              String filtersHash,
                              String mode,
                              String archiveDate,
                              int skip,
                              long snapshotInfoSize,
                              long snapshotErrorSize,
                              long snapshotMillis) {
            this.v = v;
            this.filtersHash = filtersHash;
            this.mode = mode;
            this.archiveDate = archiveDate;
            this.skip = skip;
            this.snapshotInfoSize = snapshotInfoSize;
            this.snapshotErrorSize = snapshotErrorSize;
            this.snapshotMillis = snapshotMillis;
        }
    }

    private CursorState decodeCursor(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            return CURSOR_MAPPER.readValue(json, CursorState.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String encodeCursor(CursorState state) {
        try {
            byte[] json = CURSOR_MAPPER.writeValueAsBytes(state);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            return null;
        }
    }

    private List<LogEntry> fetchTopMatchesForStream(String streamType,
                                                     String keyword,
                                                     String level,
                                                     String thread,
                                                     String logger,
                                                     LocalDate archiveDay,
                                                     long snapshotEndPos,
                                                     boolean archiveMode,
                                                     int fetchCount) throws IOException {
        Path activePath = Paths.get(getLogPath(streamType));

        List<Path> segments;
        if (archiveMode) {
            segments = listArchiveSegmentsForDate(activePath, archiveDay);
        } else {
            List<Path> s = new ArrayList<>();
            s.add(activePath);
            // 同时包含“今天日期”的 gzip 归档（如果按大小滚动产生了同日归档）。
            LocalDate today = LocalDate.now();
            s.addAll(listArchiveSegmentsForDate(activePath, today));
            segments = s;
        }

        // 快照只对活跃 plain 文件生效。
        long activeSnapshot = Math.max(0L, snapshotEndPos);

        List<LogEntry> matches = new ArrayList<>(Math.min(fetchCount, 200));
        try {
            for (Path seg : segments) {
                if (matches.size() >= fetchCount) break;
                if (seg == null || !Files.exists(seg)) continue;

                long endPos;
                if (archiveMode) {
                    endPos = Files.size(seg);
                } else {
                    endPos = seg.equals(activePath) ? activeSnapshot : Files.size(seg);
                }
                if (endPos <= 0L) continue;

                if (seg.toString().endsWith(".gz")) {
                    fetchFromGzipReverse(seg, keyword, level, thread, logger, fetchCount, matches);
                } else {
                    fetchFromPlainReverse(seg, endPos, keyword, level, thread, logger, fetchCount, matches);
                }
            }
        } catch (StopRead stop) {
        // 预计会提前停止（已满足需要数量）
        }
        return matches;
    }

    private void fetchFromPlainReverse(Path file,
                                         long endPos,
                                         String keyword,
                                         String level,
                                         String thread,
                                         String logger,
                                         int fetchCount,
                                         List<LogEntry> matches) throws IOException {
        // 反向读文件时：先读到堆栈末行，再读到首行；非标准行（堆栈）先入缓冲，遇到标准行再合并
        List<String> revStackLines = new ArrayList<>();
        List<String> lines = readLinesUtf8Prefix(file, endPos);
        for (int i = lines.size() - 1; i >= 0; i--) {
            consumeReverseFetchLine(lines.get(i), revStackLines, keyword, level, thread, logger, fetchCount, matches);
        }
    }

    /**
     * 读取文件 [0, endPos) 字节，按 UTF-8 用标准换行切分。
     * 替代按块反向扫 \n：避免块边界与多字节字符导致两行被粘成一条（如 "2 ms" 后直接接 "alhost]..."）。
     */
    private List<String> readLinesUtf8Prefix(Path path, long endPos) throws IOException {
        long size = Files.size(path);
        long nLong = Math.min(Math.max(endPos, 0L), size);
        if (nLong <= 0L) {
            return Collections.emptyList();
        }
        int n = (int) Math.min(nLong, (long) Integer.MAX_VALUE);
        byte[] buf = new byte[n];
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path.toFile(), "r")) {
            raf.readFully(buf);
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * 反向读取时合并一条日志：revStackLines 中为“从文件底部往上”的续行（顺序与文件相反），遇到标准行时反转后与头行拼接。
     */
    private void consumeReverseFetchLine(String line,
                                          List<String> revStackLines,
                                          String keyword,
                                          String level,
                                          String thread,
                                          String logger,
                                          int fetchCount,
                                          List<LogEntry> matches) {
        if (matches.size() >= fetchCount) {
            throw new StopRead();
        }
        if (line == null || line.isEmpty()) {
            return;
        }
        // BOM / 行首空白会导致 parse 与「行首时间」判定同时失败，整条被当成续行糊进上一条
        String n = normalizeLogLine(line);
        if (n.isEmpty()) {
            return;
        }

        LogEntry entry = parseLogLine(n);
        if (entry != null) {
            if (!revStackLines.isEmpty()) {
                Collections.reverse(revStackLines);
                String full = n + "\n" + String.join("\n", revStackLines);
                revStackLines.clear();
                entry.setMessage(full);
                entry.setRawLine(full);
            }

            if (keyword != null && !keyword.trim().isEmpty()) {
                String msg = entry.getMessage();
                if (msg == null || !msg.contains(keyword)) {
                    return;
                }
            }
            if (level != null && !level.trim().isEmpty()) {
                if (entry.getLevel() == null || !level.equalsIgnoreCase(entry.getLevel())) {
                    return;
                }
            }
            if (thread != null && !thread.trim().isEmpty()) {
                if (entry.getThread() == null || !entry.getThread().contains(thread)) {
                    return;
                }
            }
            if (logger != null && !logger.trim().isEmpty()) {
                if (entry.getLogger() == null || !entry.getLogger().contains(logger)) {
                    return;
                }
            }

            matches.add(entry);
            if (matches.size() >= fetchCount) {
                throw new StopRead();
            }
        } else if (isProbableLogHeaderLine(n)) {
            if (!revStackLines.isEmpty()) {
                Collections.reverse(revStackLines);
                String full = n + "\n" + String.join("\n", revStackLines);
                revStackLines.clear();
                addReverseEntryIfFiltersPass(buildLooseHeaderEntry(full), keyword, level, thread, logger, fetchCount, matches);
            } else {
                addReverseEntryIfFiltersPass(buildLooseHeaderEntry(n), keyword, level, thread, logger, fetchCount, matches);
            }
        } else {
            revStackLines.add(n);
        }
    }

    /** 去掉 BOM、trim；与 logback 行首时间判定一致 */
    private String normalizeLogLine(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceFirst("^\uFEFF", "").trim();
    }

    private boolean isProbableLogHeaderLine(String line) {
        String s = normalizeLogLine(line);
        return !s.isEmpty() && LINE_LOOKS_LIKE_LOG_HEADER.matcher(s).lookingAt();
    }

    /** 严格解析失败但明显是新日志行：提取时间戳/级别等供排序与筛选，正文保留整行 */
    private LogEntry buildLooseHeaderEntry(String text) {
        LogEntry entry = new LogEntry();
        entry.setRawLine(text);
        entry.setMessage(text);
        if (text == null) {
            return entry;
        }
        String first = (text.indexOf('\n') >= 0 ? text.substring(0, text.indexOf('\n')) : text).trim();
        Matcher ts = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})").matcher(first);
        if (ts.find()) {
            try {
                entry.setTimestamp(LocalDateTime.parse(ts.group(1),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
            } catch (Exception ignored) {
            }
        }
        int rb = first.indexOf(']');
        if (rb > 0 && rb + 1 < first.length()) {
            entry.setThread(first.substring(first.indexOf('[') + 1, rb));
            String after = first.substring(rb + 1).trim();
            int sp = after.indexOf(' ');
            if (sp > 0) {
                String lev = after.substring(0, sp);
                if (lev.matches("\\w+")) {
                    entry.setLevel(lev);
                }
            }
        }
        return entry;
    }

    private void addReverseEntryIfFiltersPass(LogEntry entry,
                                               String keyword,
                                               String level,
                                               String thread,
                                               String logger,
                                               int fetchCount,
                                               List<LogEntry> matches) {
        if (matches.size() >= fetchCount) {
            throw new StopRead();
        }
        if (entry == null) {
            return;
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String msg = entry.getMessage();
            if (msg == null || !msg.contains(keyword)) {
                return;
            }
        }
        if (level != null && !level.trim().isEmpty()) {
            if (entry.getLevel() == null || !level.equalsIgnoreCase(entry.getLevel())) {
                return;
            }
        }
        if (thread != null && !thread.trim().isEmpty()) {
            if (entry.getThread() == null || !entry.getThread().contains(thread)) {
                return;
            }
        }
        if (logger != null && !logger.trim().isEmpty()) {
            if (entry.getLogger() == null || !entry.getLogger().contains(logger)) {
                return;
            }
        }
        matches.add(entry);
        if (matches.size() >= fetchCount) {
            throw new StopRead();
        }
    }

    private void fetchFromGzipReverse(Path gzipFile,
                                       String keyword,
                                       String level,
                                       String thread,
                                       String logger,
                                       int fetchCount,
                                       List<LogEntry> matches) throws IOException {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("logviewer-", ".tmp");
            try (GZIPInputStream gis = new GZIPInputStream(Files.newInputStream(gzipFile));
                 OutputStream os = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = gis.read(buf)) >= 0) {
                    os.write(buf, 0, read);
                }
            }
            long tmpSize = Files.size(tmp);
            List<String> revStackLines = new ArrayList<>();
            List<String> gzLines = readLinesUtf8Prefix(tmp, tmpSize);
            for (int i = gzLines.size() - 1; i >= 0; i--) {
                consumeReverseFetchLine(gzLines.get(i), revStackLines, keyword, level, thread, logger, fetchCount, matches);
            }
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private List<Path> listArchiveSegmentsForDate(Path activePath, LocalDate archiveDay) {
        if (activePath == null || archiveDay == null) {
            return Collections.emptyList();
        }

        Path dir = activePath.getParent();
        if (dir == null) dir = Paths.get(".");

        String fileName = activePath.getFileName().toString();
        String prefix = fileName.endsWith(".log") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String dateStr = archiveDay.format(DateTimeFormatter.ISO_LOCAL_DATE);

        // 匹配：info.YYYY-MM-DD.i.log.gz
        String glob = prefix + "." + dateStr + ".*.log.gz";
        Pattern p = Pattern.compile("^" + Pattern.quote(prefix) + "\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.log\\.gz$");

        List<ArchiveMeta> archives = new ArrayList<>();
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
                for (Path f : stream) {
                    String name = f.getFileName().toString();
                    Matcher m = p.matcher(name);
                    if (m.matches()) {
                        LocalDate d = LocalDate.parse(m.group(1), DateTimeFormatter.ISO_LOCAL_DATE);
                        int index = Integer.parseInt(m.group(2));
                        if (archiveDay.equals(d)) {
                            archives.add(new ArchiveMeta(d, index, f));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        archives.sort((a, b) -> Integer.compare(b.index, a.index)); // i 越大越新
        List<Path> result = new ArrayList<>(archives.size());
        for (ArchiveMeta meta : archives) {
            result.add(meta.path);
        }
        return result;
    }

    private List<Path> listRollingSegments(Path activePath) {
        // 活跃文件是最新：info.log / error.log
        // 滚动归档：info.YYYY-MM-DD.i.log.gz / error...gz
        if (activePath == null) {
            return Collections.emptyList();
        }

        Path dir = activePath.getParent();
        if (dir == null) dir = Paths.get(".");

        String fileName = activePath.getFileName().toString();
        String prefix = fileName.endsWith(".log") ? fileName.substring(0, fileName.length() - 4) : fileName;

        // 先收集归档，再把活跃文件插到最前面
        List<ArchiveMeta> archives = new ArrayList<>();
        String archiveRegex = "^" + Pattern.quote(prefix) + "\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.log\\.gz$";
        Pattern p = Pattern.compile(archiveRegex);

        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + ".*.log.gz")) {
                for (Path f : stream) {
                    String name = f.getFileName().toString();
                    Matcher m = p.matcher(name);
                    if (m.matches()) {
                        archives.add(new ArchiveMeta(LocalDateTime.parse(m.group(1) + "T00:00:00").toLocalDate(), Integer.parseInt(m.group(2)), f));
                    }
                }
            } catch (IOException ignored) {
            }
        }

        archives.sort((a, b) -> {
            int c = b.date.compareTo(a.date);
            if (c != 0) return c;
        return Integer.compare(b.index, a.index); // i 越大越新
        });

        List<Path> segments = new ArrayList<>(1 + archives.size());
        segments.add(activePath);
        for (ArchiveMeta meta : archives) {
            segments.add(meta.path);
        }
        return segments;
    }

    private static class ArchiveMeta {
        public java.time.LocalDate date;
        public int index;
        public Path path;

        public ArchiveMeta(java.time.LocalDate date, int index, Path path) {
            this.date = date;
            this.index = index;
            this.path = path;
        }
    }

    private static class StopRead extends RuntimeException {}

    public void startRealTimeStreaming(WebSocketSession session) {
        // 合并实时推送：同时推送 info + error，页面可统一显示所有级别。
        Thread thread = new Thread(() -> {
            try {
                Path infoPath = Paths.get(getLogPath("info"));
                Path errorPath = Paths.get(getLogPath("error"));
                // 用文件末尾字节偏移初始化，避免对大文件执行 readAllLines / 全量行数统计导致连接瞬间卡死
                long lastPosInfo = tailInitialFilePosition(infoPath);
                long lastPosError = tailInitialFilePosition(errorPath);

                while (session.isOpen()) {
                    try {
                        lastPosInfo = streamNewTailBytes(infoPath, lastPosInfo);
                    } catch (Exception ex) {
                        log.debug("tail info stream failed: " + infoPath, ex);
                    }
                    try {
                        lastPosError = streamNewTailBytes(errorPath, lastPosError);
                    } catch (Exception ex) {
                        log.debug("tail error stream failed: " + errorPath, ex);
                    }

                    Thread.sleep(1000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Error in real-time streaming(combined)", e);
            }
        });

        streamingThreads.put(session, thread);
        thread.start();
    }

    private long tailInitialFilePosition(Path logPath) {
        try {
            if (logPath == null || !Files.exists(logPath)) {
                return 0L;
            }
            return Files.size(logPath);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 自上次字节位置增量读取新日志，避免每秒 {@code Files.readAllLines} 整文件读入导致内存与 CPU 飙升。
     */
    private long streamNewTailBytes(Path logPath, long lastFilePosition) throws Exception {
        if (logPath == null || !Files.exists(logPath)) {
            return lastFilePosition;
        }
        String key = logPath.toAbsolutePath().toString();
        long size = Files.size(logPath);
        if (size < lastFilePosition) {
            wsPendingTailLines.remove(key);
            wsTailBytePartial.remove(key);
            return size;
        }
        if (size <= lastFilePosition) {
            return lastFilePosition;
        }
        long delta = size - lastFilePosition;
        if (delta > MAX_LOG_TAIL_DELTA_BYTES) {
            log.warn("log tail skip: {} grew by {} bytes since last read, advancing cursor to avoid OOM",
                logPath.getFileName(), delta);
            wsTailBytePartial.remove(key);
            return size;
        }
        byte[] buf = new byte[(int) delta];
        try (RandomAccessFile raf = new RandomAccessFile(logPath.toFile(), "r")) {
            raf.seek(lastFilePosition);
            raf.readFully(buf);
        }
        String decoded = new String(buf, StandardCharsets.UTF_8);
        String merged = wsTailBytePartial.getOrDefault(key, "") + decoded;
        int lastNl = merged.lastIndexOf('\n');
        if (lastNl < 0) {
            wsTailBytePartial.put(key, merged);
            return size;
        }
        String linesPart = merged.substring(0, lastNl + 1);
        String remainder = merged.substring(lastNl + 1);
        if (remainder.isEmpty()) {
            wsTailBytePartial.remove(key);
        } else {
            wsTailBytePartial.put(key, remainder);
        }
        List<String> newLines = splitPhysicalLogLines(linesPart);
        if (!newLines.isEmpty()) {
            emitForwardGroupedLinesWithPending(logPath, newLines);
        }
        return size;
    }

    private static List<String> splitPhysicalLogLines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '\n') {
                String line = text.substring(start, i);
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                    line = line.substring(0, line.length() - 1);
                }
                out.add(line);
                start = i + 1;
            }
        }
        return out;
    }

    /**
     * 正向多行合并：堆栈续行归入上一条；跨轮询片段先缓冲，避免拆成两次推送导致重复。
     */
    private void emitForwardGroupedLinesWithPending(Path logPath, List<String> newLines) throws Exception {
        String key = logPath.toAbsolutePath().toString();
        List<String> prefix = wsPendingTailLines.remove(key);
        List<String> combined = new ArrayList<>();
        if (prefix != null && !prefix.isEmpty()) {
            combined.addAll(prefix);
        }
        combined.addAll(newLines);

        List<String> group = new ArrayList<>();
        for (String line : combined) {
            if (parseLogLine(line) != null) {
                if (!group.isEmpty()) {
                    sendMergedLogGroup(group);
                    group.clear();
                }
                group.add(line);
            } else if (isProbableLogHeaderLine(line)) {
                if (!group.isEmpty()) {
                    sendMergedLogGroup(group);
                    group.clear();
                }
                sendLooseHeaderAsJson(line);
            } else {
                if (!group.isEmpty()) {
                    group.add(line);
                }
            }
        }
        if (group.isEmpty()) {
            return;
        }
        String last = combined.get(combined.size() - 1);
        if (parseLogLine(last) == null && !isProbableLogHeaderLine(last)) {
            wsPendingTailLines.put(key, new ArrayList<>(group));
        } else {
            sendMergedLogGroup(group);
        }
    }

    private void sendLooseHeaderAsJson(String line) throws Exception {
        LogEntry entry = buildLooseHeaderEntry(line);
        String json = CURSOR_MAPPER.writeValueAsString(entry);
        webSocketHandler.sendLogToClients(json);
    }

    private void sendMergedLogGroup(List<String> group) throws Exception {
        if (group == null || group.isEmpty()) {
            return;
        }
        String first = group.get(0);
        LogEntry entry = parseLogLine(first);
        if (entry == null) {
            webSocketHandler.sendLogToClients(first);
            return;
        }
        if (group.size() > 1) {
            String full = first + "\n" + String.join("\n", group.subList(1, group.size()));
            entry.setMessage(full);
            entry.setRawLine(full);
        }
        String json = CURSOR_MAPPER.writeValueAsString(entry);
        webSocketHandler.sendLogToClients(json);
    }

    public void startRealTimeStreaming(WebSocketSession session, String type) {
        Thread thread = new Thread(() -> {
            try {
                Path logPath = Paths.get(getLogPath(type));
                long lastPos = tailInitialFilePosition(logPath);
                while (session.isOpen()) {
                    if (!Files.exists(logPath)) {
                        Thread.sleep(1000);
                        continue;
                    }
                    try {
                        lastPos = streamNewTailBytes(logPath, lastPos);
                    } catch (Exception ex) {
                        log.debug("streamNewTailBytes failed", ex);
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Error in real-time streaming", e);
            }
        });
        streamingThreads.put(session, thread);
        thread.start();
    }

    public void stopRealTimeStreaming(WebSocketSession session) {
        Thread thread = streamingThreads.remove(session);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private List<String> readLogFile(String path) throws IOException {
        Path filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            return Collections.emptyList();
        }

        if (path.endsWith(".gz")) {
            try (GZIPInputStream gis = new GZIPInputStream(Files.newInputStream(filePath));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(gis))) {
                return reader.lines().collect(Collectors.toList());
            }
        } else {
            return Files.readAllLines(filePath);
        }
    }

    private List<LogEntry> parseLogLines(List<String> lines) {
        List<LogEntry> out = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (String line : lines) {
            if (parseLogLine(line) != null) {
                if (!group.isEmpty()) {
                    LogEntry merged = mergeForwardLogGroup(group);
                    if (merged != null) {
                        out.add(merged);
                    }
                    group.clear();
                }
                group.add(line);
            } else if (isProbableLogHeaderLine(line)) {
                if (!group.isEmpty()) {
                    LogEntry merged = mergeForwardLogGroup(group);
                    if (merged != null) {
                        out.add(merged);
                    }
                    group.clear();
                }
                out.add(buildLooseHeaderEntry(line));
            } else {
                if (!group.isEmpty()) {
                    group.add(line);
                }
            }
        }
        if (!group.isEmpty()) {
            LogEntry merged = mergeForwardLogGroup(group);
            if (merged != null) {
                out.add(merged);
            }
        }
        return out;
    }

    private LogEntry mergeForwardLogGroup(List<String> group) {
        if (group == null || group.isEmpty()) {
            return null;
        }
        String first = group.get(0);
        LogEntry entry = parseLogLine(first);
        if (entry == null) {
            return null;
        }
        if (group.size() > 1) {
            String full = first + "\n" + String.join("\n", group.subList(1, group.size()));
            entry.setMessage(full);
            entry.setRawLine(full);
        }
        return entry;
    }

    private LogEntry parseLogLine(String line) {
        if (line == null) {
            return null;
        }
        line = normalizeLogLine(line);
        if (line.isEmpty()) {
            return null;
        }

        DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        Matcher logback = LOG_PATTERN_LOGBACK_LINE.matcher(line);
        if (logback.matches()) {
            LogEntry entry = new LogEntry();
            entry.setTimestamp(LocalDateTime.parse(logback.group(1), tsFmt));
            entry.setThread(logback.group(2));
            entry.setLevel(logback.group(3));
            entry.setLogger(logback.group(4));
            entry.setMessage(line);
            entry.setRawLine(line);
            return entry;
        }

        Matcher hyphen = LOG_PATTERN_HYPHEN.matcher(line);
        if (hyphen.matches()) {
            LogEntry entry = new LogEntry();
            entry.setTimestamp(LocalDateTime.parse(hyphen.group(1), tsFmt));
            entry.setThread(hyphen.group(2));
            entry.setLevel(hyphen.group(3));
            entry.setLogger(hyphen.group(4).trim());
            // 为了在页面上展示“完整前缀 + 消息”，将 message 直接设置为整行原始日志。
            // 这样即便前端回退到 message 字段，也能看到 timestamp/thread/level/logger 等信息。
            entry.setMessage(line);
            entry.setRawLine(line);
            return entry;
        }

        Matcher colon = LOG_PATTERN_COLON.matcher(line);
        if (colon.matches()) {
            LogEntry entry = new LogEntry();
            entry.setTimestamp(LocalDateTime.parse(colon.group(1), tsFmt));
            entry.setThread(colon.group(2));
            entry.setLevel(colon.group(3));
            entry.setLogger(colon.group(4));
            entry.setMessage(line);
            entry.setRawLine(line);
            return entry;
        }

        return null;
    }
}
