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

    /** HS256 최소 키 길이 (RFC 7518 3.2) — 32바이트 미만이면 jjwt 가 거부한다 */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtTokenProvider(JwtProperties properties) {
        byte[] secret = secretBytes(properties.secret());
        this.key = Keys.hmacShaKeyFor(secret);
        this.expiryMillis = properties.expiryMinutes() * 60_000L;
    }

    /**
     * 비밀키를 확인한다. **기본값을 두지 않으므로** 값이 없으면 여기서 멈춰야 한다.
     *
     * 그냥 두면 jjwt 가 "key byte array is 104 bits" 라고만 말해서, 키를 안 넣은 것인지
     * 짧게 넣은 것인지 알 수 없다 (104비트 = 치환되지 않은 `${JWT_SECRET}` 13글자).
     * 무엇을 어디에 넣어야 하는지까지 말해 준다.
     */
    private static byte[] secretBytes(String secret) {
        if (secret == null || secret.isBlank() || secret.startsWith("${")) {
            throw new IllegalStateException(
                    "JWT_SECRET 이 설정되지 않았습니다 — .env 에 32바이트 이상으로 넣으세요 "
                            + "(.env.example 참고). 공개 저장소에 올라가므로 기본값을 두지 않습니다");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 이 " + bytes.length + "바이트로 너무 짧습니다 — HS256 은 "
                            + MIN_SECRET_BYTES + "바이트 이상이어야 합니다 (RFC 7518 3.2)");
        }
        return bytes;
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
