package com.lm.codeprep.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${codeprep.security.jwt-secret}") String secret,
                      @Value("${codeprep.security.token-ttl-hours:12}") long hours) {
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        ttl = Duration.ofHours(hours);
    }

    public String issue(UserPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder().subject(principal.getUsername())
                .claim("roles", principal.account().roles())
                .claim("orgTags", principal.account().organizationTags())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl)))
                .signWith(key).compact();
    }

    public String username(String token) {
        return claims(token).getSubject();
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
