# Log Viewer Starter

`log-viewer` 已重构为多模块工程，同时支持 Spring Boot 2 和 Spring Boot 3：

- `log-viewer-core`：共享模型（`LogEntry`、`CursorPageResponse` 等）
- `log-viewer-boot2-starter`：Boot2 Starter（`javax.*`）
- `log-viewer-boot3-starter`：Boot3 Starter（`jakarta.*`）

功能保持一致：

- REST 拉取日志（游标分页）
- WebSocket 实时推送日志
- 归档日志读取（`.gz`）
- 访问密钥校验

页面入口和 WS 入口：

- `GET /logviewer`
- `/logviewer/ws/logs`

---

## 1. Maven 依赖

### Spring Boot 2.x 项目

```xml
<dependency>
  <groupId>com.thy</groupId>
  <artifactId>log-viewer-boot2-starter</artifactId>
  <version>1.0.1</version>
</dependency>
```

### Spring Boot 3.x 项目

```xml
<dependency>
  <groupId>com.thy</groupId>
  <artifactId>log-viewer-boot3-starter</artifactId>
  <version>1.0.1</version>
</dependency>
```

---

## 2. 配置说明

必填配置：

```yaml
logviewer:
  accessKey: your-secret-key
```

可选配置：

```yaml
logviewer:
  enabled: true
  autoDetectLogback: true
  maxConnections: 2
```

---

## 3. 访问方式

打开页面：

`http://<host>:<port>/logviewer?key=<accessKey>`

分页接口：

- `POST /logviewer/logs`
- 参数：`cursor`、`keyword`、`level`、`thread`、`logger`、`archiveDate`、`size`

WebSocket：

- `ws(s)://<host>:<port>/logviewer/ws/logs`

---

## 4. 兼容性说明（重要）

为避免接入方未开启 `-parameters` 导致：

`Name for argument of type [java.lang.String] not specified...`

Boot2 和 Boot3 Starter 已统一将 `@RequestParam` 参数名改为显式 `value`，不再依赖反射参数名。

---

## 5. 构建与安装

在 `log-viewer` 目录下构建全部模块：

```bash
mvn -DskipTests clean install
```

仅构建 Boot3 Starter：

```bash
mvn -pl log-viewer-boot3-starter -am -DskipTests clean package
```

仅构建 Boot2 Starter：

```bash
mvn -pl log-viewer-boot2-starter -am -DskipTests clean package
```

