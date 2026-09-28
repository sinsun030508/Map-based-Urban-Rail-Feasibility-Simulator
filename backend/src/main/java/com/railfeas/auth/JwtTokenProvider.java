package com.railfeas.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import com.railfeas.user.Role;
import com.railfeas.user.User;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expiryMillis;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expiryMillis = properties.expiryMinutes() * 60_000L;
    }

    public String createToken(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMillis))
                .signWith(key)
                .compact();
    }

    /** 유효하지 않으면 null — 필터에서 익명 요청으로 흘려보낸다. */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public Role role(Claims claims) {
        return Role.valueOf(claims.get("role", String.class));
    }

    public Long userId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }
}
