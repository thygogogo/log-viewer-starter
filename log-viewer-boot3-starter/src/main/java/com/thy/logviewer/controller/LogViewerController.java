package com.thy.logviewer.controller;

import com.thy.logviewer.config.LogViewerProperties;
import com.thy.logviewer.model.LogEntry;
import com.thy.logviewer.model.CursorPageResponse;
import com.thy.logviewer.service.LogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;

@Controller
public class LogViewerController {

    private final LogService logService;
    private final LogViewerProperties properties;

    public LogViewerController(LogService logService, LogViewerProperties properties) {
        this.logService = logService;
        this.properties = properties;
    }

    @GetMapping("/logviewer")
    public String index(@RequestParam(value = "key", required = false) String key, Model model, HttpServletRequest request) {
        // 用于模板区分：避免引入方如果返回了同名视图（例如 viewName="index"），误把日志 UI 渲染出来
        model.addAttribute("logviewerPage", true);
        String accessKey = properties.getAccessKey();
        if (key == null || !key.equals(accessKey)) {
            request.setAttribute("error", "需要有效的访问key");
            return "key-required";
        }
        return "index";
    }

    @PostMapping("/logviewer/logs")
    @ResponseBody
    public CursorPageResponse<LogEntry> getLogs(@RequestParam(value = "cursor", required = false) String cursor,
                                                  @RequestParam(value = "keyword", required = false) String keyword,
                                                  @RequestParam(value = "level", required = false) String level,
                                                  @RequestParam(value = "thread", required = false) String thread,
                                                  @RequestParam(value = "logger", required = false) String logger,
                                                  @RequestParam(value = "archiveDate", required = false) String archiveDate,
                                                  @RequestParam(value = "size", defaultValue = "100") int size) {
        return logService.getLogsCursor(keyword, level, thread, logger, archiveDate, cursor, size);
    }

    // WebSocket接口将在后续添加
}
