package com.thy.logviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "logviewer")
public class LogViewerProperties {

    private boolean enabled = true;

    private String logPath = "/var/log/app.log";

    private boolean autoDetectLogback = true;

    @NotBlank(message = "配置项 logviewer.accessKey 不能为空，请在 application.yml 中配置访问密钥")
    private String accessKey;

    @Min(value = 1, message = "配置项 logviewer.maxConnections 必须 >= 1")
    private int maxConnections = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLogPath() {
        return logPath;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    public boolean isAutoDetectLogback() {
        return autoDetectLogback;
    }

    public void setAutoDetectLogback(boolean autoDetectLogback) {
        this.autoDetectLogback = autoDetectLogback;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }
}
