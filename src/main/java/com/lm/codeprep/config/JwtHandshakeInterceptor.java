package com.lm.codeprep.config;

import com.lm.codeprep.auth.AccountService;
import com.lm.codeprep.auth.JwtService;
import com.lm.codeprep.auth.UserPrincipal;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

public class JwtHandshakeInterceptor implements HandshakeInterceptor {
    private final JwtService jwtService;
    private final AccountService accounts;
    public JwtHandshakeInterceptor(JwtService jwtService, AccountService accounts) { this.jwtService = jwtService; this.accounts = accounts; }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        String token = request.getURI().getQuery();
        if (token == null || !token.contains("token=")) return false;
        try {
            String raw = java.net.URLDecoder.decode(token.substring(token.indexOf("token=") + 6).split("&")[0], java.nio.charset.StandardCharsets.UTF_8);
            UserPrincipal principal = accounts.load(jwtService.username(raw));
            if (principal == null) return false;
            attributes.put("authentication", new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            return true;
        } catch (RuntimeException exception) { return false; }
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) { }
}
