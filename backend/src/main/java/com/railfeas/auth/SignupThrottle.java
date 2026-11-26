package com.railfeas.auth;

import com.railfeas.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 가입을 접속 주소마다 **한 시간에 N번**까지 받는다(`app.signup.max-per-hour`, 기본 10 — 판단값, 11-26 보안 점검).
 *
 * <p>제한이 없어 4.4초에 15번 가입됐다. 가입마다 비밀번호 해시(bcrypt)에 CPU 가 약 0.25초라 쏟아부으면 계정 쓰레기와 함께 CPU 가 바닥난다.
 * <b>성공이든 중복(409)이든 시도마다 센다</b> — 「이미 가입된 이메일」 응답으로 가입 여부를 캐는 속도도 함께 막는다(근본 해결은 이메일 인증이다).
 * 접속 주소는 nginx 가 덮어쓴 값이라 `X-Forwarded-For` 로 비켜 가지 못한다. Redis 에는 주소의 해시만 담는다.
 *
 * <p><b>Redis 가 죽으면 막지 않는다</b> — 보조 장치가 가입을 멈추면 안 된다(`LoginThrottle` 과 같다).
 */
@Component
public class SignupThrottle {

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(SignupThrottle.class);

    private final AttemptCounter counter;
    private final int maxPerHour;

    public SignupThrottle(AttemptCounter counter, @Value("${app.signup.max-per-hour:10}") int maxPerHour) {
        this.counter = counter;
        this.maxPerHour = maxPerHour;
    }

    public void check(String address) {
        long attempts;
        try {
            attempts = counter.current(key(address));
        } catch (RuntimeException e) {
            log.warn("가입 횟수를 못 읽어 제한 없이 넘어갑니다 — {}", e.getMessage());
            return;
        }
        if (attempts >= maxPerHour) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "가입 시도가 너무 많습니다 — 한 시간 뒤에 다시 시도해 주세요");
        }
    }

    public void attempted(String address) {
        try {
            counter.increment(key(address), WINDOW);
        } catch (RuntimeException e) {
            log.warn("가입 시도를 못 셌습니다 — {}", e.getMessage());
        }
    }

    private static String key(String address) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8));
            return "railfeas:signup:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
