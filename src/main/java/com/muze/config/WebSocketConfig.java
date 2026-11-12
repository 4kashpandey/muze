package com.muze.config;

import com.muze.websocket.MuzeHandshakeInterceptor;
import com.muze.websocket.MuzeWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MuzeWebSocketHandler inSyncWebSocketHandler;
    private final MuzeHandshakeInterceptor inSyncHandshakeInterceptor;

    public WebSocketConfig(
                MuzeWebSocketHandler muzeWebSocketHandler,
            MuzeHandshakeInterceptor muzeHandshakeInterceptor
    ) {
        this.inSyncWebSocketHandler = muzeWebSocketHandler;
        this.inSyncHandshakeInterceptor = muzeHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(inSyncWebSocketHandler, "/ws")
                .addInterceptors(inSyncHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}

