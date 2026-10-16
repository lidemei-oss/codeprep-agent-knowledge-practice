package com.lm.codeprep.config;

import com.lm.codeprep.auth.AccountService;
import com.lm.codeprep.auth.JwtService;
import com.lm.codeprep.knowledge.AgentWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final AgentWebSocketHandler handler; private final JwtService jwt; private final AccountService accounts;
    public WebSocketConfig(AgentWebSocketHandler handler, JwtService jwt, AccountService accounts) { this.handler = handler; this.jwt = jwt; this.accounts = accounts; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/agent").addInterceptors(new JwtHandshakeInterceptor(jwt, accounts))
                .setAllowedOrigins("http://localhost:5500", "http://127.0.0.1:5500");
    }
}
