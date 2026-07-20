package com.geekmall.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper mapper;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionOwners = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText("PING");
        if ("JOIN".equals(type)) {
            sessionOwners.put(session.getId(), node.path("sessionNo").asText());
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                    "type", "JOINED",
                    "sessionNo", node.path("sessionNo").asText(),
                    "ts", System.currentTimeMillis()))));
            return;
        }
        if ("CHAT".equals(type)) {
            String sessionNo = node.path("sessionNo").asText();
            String payload = mapper.writeValueAsString(Map.of(
                    "type", "CHAT",
                    "sessionNo", sessionNo,
                    "senderType", node.path("senderType").asText("USER"),
                    "content", node.path("content").asText(""),
                    "ts", System.currentTimeMillis()));
            for (Map.Entry<String, String> e : sessionOwners.entrySet()) {
                if (sessionNo.equals(e.getValue())) {
                    WebSocketSession target = sessions.get(e.getKey());
                    if (target != null && target.isOpen()) {
                        target.sendMessage(new TextMessage(payload));
                    }
                }
            }
            return;
        }
        session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of("type", "PONG", "ts", System.currentTimeMillis()))));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        sessionOwners.remove(session.getId());
    }
}
