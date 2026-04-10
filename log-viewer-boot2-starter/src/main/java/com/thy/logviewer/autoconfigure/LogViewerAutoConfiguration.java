package com.thy.logviewer.autoconfigure;

import com.thy.logviewer.config.LogViewerProperties;
import com.thy.logviewer.config.LogWebSocketHandler;
import com.thy.logviewer.config.WebSocketConfig;
import com.thy.logviewer.controller.LogViewerController;
import com.thy.logviewer.service.LogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(LogViewerController.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "logviewer", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LogViewerProperties.class)
@Import(WebSocketConfig.class)
public class LogViewerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LogService logService(@Lazy LogWebSocketHandler logWebSocketHandler, LogViewerProperties properties) {
        return new LogService(logWebSocketHandler, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public LogWebSocketHandler logWebSocketHandler(@Lazy LogService logService, LogViewerProperties properties) {
        return new LogWebSocketHandler(logService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public LogViewerController logViewerController(LogService logService, LogViewerProperties properties) {
        return new LogViewerController(logService, properties);
    }
}
