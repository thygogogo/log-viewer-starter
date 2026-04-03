# Log Viewer Starter（`log-viewer`）

这是一个用于在 **Spring Boot ** 中直接引入的日志查看组件（Starter 形态），支持：

- REST 拉取日志（游标分页）
- WebSocket 实时推送日志
- 归档日志（按 `.gz` 日期）
- 访问密钥校验（未配置或 key 不正确会失败/拒绝）

> 注意：当前页面入口是 `GET /logviewer`，WebSocket 入口为 `/logviewer/ws/logs`，以避免与引入方根路径冲突。

---

## 1. 引入方式（Maven）

在引入方 `pom.xml` 加依赖（坐标以你当前仓库为准）：

```xml
<dependency>
  <groupId>com.thy</groupId>
  <artifactId>log-viewer</artifactId>
  <version>1.0.1</version>
</dependency>
```

---

## 2. 必填配置：`logviewer.accessKey`

引入方需要在 `application.yml` / `application.properties` 配置：

```yaml
logviewer:
  accessKey: 你的访问密钥
```

- 如果未配置 `logviewer.accessKey`：应用启动会报错（fail-fast），并提示需要配置该项。

---

## 3. 其他可选配置

```yaml
logviewer:
  enabled: true                  # 是否启用该 Starter（默认 true）
  autoDetectLogback: true        # 是否自动从 logback-spring.xml 探测 info/error 日志文件路径（默认 true）
  maxConnections: 2              # WebSocket 最大同时查看连接数（默认 2）
```

---

## 4. 使用方式

### 4.1 打开页面

浏览器打开：

`http://<host>:<port>/logviewer?key=<accessKey>`

- 输入的 `key` 必须与 `logviewer.accessKey` 一致
- key 校验失败会展示 `key-required` 页面

页面包含：

- 关键词查询（`keyword`）
- 翻页/“显示更多”（游标分页）
- “刷新”重新拉取最新页
- “实时日志”开关（控制是否连接 WebSocket）
- 归档模式（选择日期后拉取对应 `.gz`）

### 4.2 REST 接口

日志分页接口：

- `POST /logviewer/logs`

请求参数（均为表单参数/Query 参数）：

- `cursor`（可选）：上一页返回的游标，用于“显示更多”
- `keyword`（可选）：关键词过滤
- `level`（可选）：日志级别过滤
- `thread`（可选）：线程过滤
- `logger`（可选）：logger 过滤
- `archiveDate`（可选）：归档模式日期（按 `.gz`）
- `size`（可选，默认 `100`）：每页条数（前端也固定使用 100）

响应中会包含：

- `content`：当前页日志
- `hasNext`：是否还有下一页
- `nextCursor`：下一次请求用的游标

### 4.3 WebSocket 实时

WebSocket 地址：

- `ws(s)://<host>:<port>/logviewer/ws/logs`

服务端会推送日志 JSON（结构为 `LogEntry`），前端解析后追加到列表中。

连接数达到 `logviewer.maxConnections` 会被关闭，并发送：

`TOO_MANY_CONNECTIONS:当前访问人数已达上限`

---

## 5. 与引入方的路由冲突提示（重要）

该 starter 已做了路由名字空间化：

- 页面入口：`GET /logviewer`
- WebSocket 入口：`/logviewer/ws/logs`

一般不会再与引入方的根路径（`/`）产生冲突。

---

## 6. 构建

在本仓库根目录：

```bash
mvn -pl demo-log-viewer -DskipTests package
```

