package com.muze.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

@Component
public class MuzeHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_ROOM_ID = "roomId";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_IS_GUEST = "isGuest";

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            var req = servletRequest.getServletRequest();
            String roomId = req.getParameter("roomId");
            String userId = req.getParameter("userId");
            String username = req.getParameter("username");
            String isGuestParam = req.getParameter("isGuest");

            if (roomId == null || roomId.isBlank()) {
                return false; // Room ID is strictly required to establish sync session
            }

            if (userId == null || userId.isBlank()) {
                userId = "usr_" + UUID.randomUUID().toString().substring(0, 8);
            }
            if (username == null || username.isBlank()) {
                username = "Guest-" + UUID.randomUUID().toString().substring(0, 4);
            }

            boolean isGuest = "true".equalsIgnoreCase(isGuestParam) || username.startsWith("Guest-");

            attributes.put(ATTR_ROOM_ID, roomId.trim().toUpperCase());
            attributes.put(ATTR_USER_ID, userId.trim());
            attributes.put(ATTR_USERNAME, username.trim());
            attributes.put(ATTR_IS_GUEST, isGuest);
        }
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // Handshake complete
    }
}

