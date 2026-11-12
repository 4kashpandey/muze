package com.muze.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muze.websocket.dto.MuzeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Thread-safe registry managing active WebSocket sessions across synchronization rooms.
 * Protects against slow-consumer memory bloat and concurrent frame collision using bounded decorators.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 262144; // 256 KB buffer cap per client

    public record SessionInfo(
            String sessionId,
            String roomId,
            String userId,
            String username,
            WebSocketSession session
    ) {}

    private final ObjectMapper objectMapper;
    private final Map<String, SessionInfo> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> roomSessionsMap = new ConcurrentHashMap<>();

    public SessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(WebSocketSession rawSession, String roomId, String userId, String username) {
        // Wrap with ConcurrentWebSocketSessionDecorator to solve slow-consumer OOM & lock contention
        WebSocketSession decoratedSession = new ConcurrentWebSocketSessionDecorator(
                rawSession,
                SEND_TIME_LIMIT_MS,
                BUFFER_SIZE_LIMIT_BYTES
        );

        SessionInfo info = new SessionInfo(rawSession.getId(), roomId, userId, username, decoratedSession);
        sessionMap.put(rawSession.getId(), info);
        roomSessionsMap.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>()).add(rawSession.getId());
        log.debug("Session registered with bounded buffer: [{}] for user [{}] in room [{}]", rawSession.getId(), userId, roomId);
    }

    public Optional<SessionInfo> unregister(String sessionId) {
        SessionInfo info = sessionMap.remove(sessionId);
        if (info != null) {
            Set<String> sessions = roomSessionsMap.get(info.roomId());
            if (sessions != null) {
                sessions.remove(sessionId);
                if (sessions.isEmpty()) {
                    roomSessionsMap.remove(info.roomId());
                }
            }
            log.debug("Session unregistered: [{}] for user [{}] in room [{}]", sessionId, info.userId(), info.roomId());
            return Optional.of(info);
        }
        return Optional.empty();
    }

    public Optional<SessionInfo> getSessionInfo(String sessionId) {
        return Optional.ofNullable(sessionMap.get(sessionId));
    }

    public void broadcastToRoom(String roomId, MuzeMessage message) {
        Set<String> sessionIds = roomSessionsMap.get(roomId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(json);

            for (String sId : sessionIds) {
                SessionInfo info = sessionMap.get(sId);
                if (info != null && info.session().isOpen()) {
                    sendMessageSafely(info.session(), textMessage);
                }
            }
        } catch (IOException e) {
            log.error("Failed to serialize broadcast message for room {}: {}", roomId, e.getMessage());
        }
    }

    public void sendToSession(WebSocketSession session, MuzeMessage message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            sendMessageSafely(session, new TextMessage(json));
        } catch (IOException e) {
            log.error("Failed to send direct message to session {}: {}", session.getId(), e.getMessage());
        }
    }

    public void closeRoomSessions(String roomId, String reason) {
        Set<String> sessionIds = roomSessionsMap.remove(roomId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        CloseStatus status = new CloseStatus(CloseStatus.NORMAL.getCode(), reason);
        for (String sId : sessionIds) {
            SessionInfo info = sessionMap.remove(sId);
            if (info != null && info.session().isOpen()) {
                try {
                    info.session().close(status);
                } catch (IOException e) {
                    log.warn("Error closing session [{}] for room [{}]: {}", sId, roomId, e.getMessage());
                }
            }
        }
    }

    private void sendMessageSafely(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        } catch (IOException e) {
            log.warn("Error sending WebSocket message to session {}: {}", session.getId(), e.getMessage());
        }
    }
}
