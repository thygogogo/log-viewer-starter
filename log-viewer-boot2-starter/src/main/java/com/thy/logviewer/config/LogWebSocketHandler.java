package com.thy.logviewer.config;

import com.thy.logviewer.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArraySet;

public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocketHandler.class);

    private final LogService logService;
    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final LogViewerProperties properties;

    public LogWebSocketHandler(@Lazy LogService logService, LogViewerProperties properties) {
        this.logService = logService;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        int maxConnections = Math.max(1, properties.getMaxConnections());
        if (sessions.size() >= maxConnections) {
            session.sendMessage(new TextMessage("TOO_MANY_CONNECTIONS:当前访问人数已达上限"));
            session.close();
            return;
        }
        sessions.add(session);
        // Start streaming logs
        logService.startRealTimeStreaming(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
        sessions.remove(session);
        logService.stopRealTimeStreaming(session);
    }

    public void sendLogToClients(String logLine) {
        for (WebSocketSession session : sessions) {
            try {
                session.sendMessage(new TextMessage(logLine));
            } catch (IOException e) {
                // Handle error
            }
        }
    }
}
