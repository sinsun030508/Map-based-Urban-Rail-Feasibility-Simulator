package com.railfeas.auth;

import com.railfeas.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 무차별 대입을 막는다 — 같은 (이메일, 접속 주소)로 {@value #MAX_FAILURES}번 틀리면 15분 기다린다(판단값).
 *
 * <p><b>이메일만 묶지 않는다</b> — 그러면 남의 주소로 틀려 주는 것만으로 그 사람을 잠글 수 있다. 접속 주소는 nginx 가 덮어쓴 값이라
 * 손님이 `X-Forwarded-For` 를 바꿔 비켜 가지 못한다(`nginx.conf.template`). Redis 에는 해시만 담는다 — 이메일이 남지 않는다.
 *
 * <p><b>Redis 가 죽으면 막지 않는다.</b> 보조 장치가 로그인을 멈추면 안 된다 — 대신 경고를 남긴다.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final AttemptCounter counter;

    public LoginThrottle(AttemptCounter counter) {
        this.counter = counter;
    }

    public void check(String email, String address) {
        long failures;
        try {
            failures = counter.current(key(email, address));
        } catch (RuntimeException e) {
            log.warn("로그인 횟수를 못 읽어 제한 없이 넘어갑니다 — {}", e.getMessage());
            return;
        }
        if (failures >= MAX_FAILURES) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "비밀번호를 여러 번 틀렸습니다 — 15분 뒤에 다시 시도해 주세요");
        }
    }

    public void failed(String email, String address) {
        try {
            counter.increment(key(email, address), WINDOW);
        } catch (RuntimeException e) {
            log.warn("로그인 실패를 못 셌습니다 — {}", e.getMessage());
        }
    }

    public void succeeded(String email, String address) {
        try {
            counter.clear(key(email, address));
        } catch (RuntimeException e) {
            log.warn("로그인 횟수를 못 지웠습니다 — {}", e.getMessage());
        }
    }

    private static String key(String email, String address) {
        String text = email.toLowerCase(Locale.ROOT) + "|" + address;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "railfeas:login:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
