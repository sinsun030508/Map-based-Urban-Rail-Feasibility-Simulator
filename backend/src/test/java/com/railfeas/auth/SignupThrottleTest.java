package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.railfeas.common.ApiException;
import com.railfeas.user.UserRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 가입을 **접속 주소마다 한 시간에 N번**까지(11-26 보안 점검, 판단값). 제한이 없어 4.4초에 15번이 됐고, 가입마다 비밀번호 해시(bcrypt)에 CPU 가 약 0.25초라
 * 가입을 쏟아부으면 계정 쓰레기와 함께 CPU 가 바닥났다. **중복 이메일(409)도 시도로 센다** — 「이미 가입된 이메일」 응답으로 가입 여부를 캐는 속도를 막는다.
 * 로그인 제한(`LoginThrottle`)과 같은 카운터를 쓰고, **Redis 가 죽으면 막지 않는다**(보조 장치가 가입을 멈추면 안 된다).
 */
class SignupThrottleTest {

    private static class MemoryCounter implements AttemptCounter {
        final Map<String, Long> map = new HashMap<>();
        Duration window;

        @Override
        public long current(String key) {
            return map.getOrDefault(key, 0L);
        }

        @Override
        public void increment(String key, Duration window) {
            map.merge(key, 1L, Long::sum);
            this.window = window;
        }

        @Override
        public void clear(String key) {
            map.remove(key);
        }
    }

    private static class BrokenCounter implements AttemptCounter {
        @Override
        public long current(String key) {
            throw new IllegalStateException("Redis 연결 실패");
        }

        @Override
        public void increment(String key, Duration window) {
            throw new IllegalStateException("Redis 연결 실패");
        }

        @Override
        public void clear(String key) {
            throw new IllegalStateException("Redis 연결 실패");
        }
    }

    private static HttpStatus statusOf(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e.getStatus();
        }
        return HttpStatus.OK;
    }

    @Test
    @DisplayName("한도까지는 가입되고 그 위는 429 — 한 시간 창으로 센다")
    void blocksAfterTheLimit() {
        MemoryCounter counter = new MemoryCounter();
        SignupThrottle throttle = new SignupThrottle(counter, 3);
        for (int i = 0; i < 3; i++) {
            throttle.check("1.2.3.4");
            throttle.attempted("1.2.3.4");
        }
        assertThatThrownBy(() -> throttle.check("1.2.3.4")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(counter.window).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("다른 주소는 따로 센다 — 한 사람이 막혀도 다른 사람은 가입된다")
    void otherAddressesAreNotBlocked() {
        SignupThrottle throttle = new SignupThrottle(new MemoryCounter(), 1);
        throttle.check("1.2.3.4");
        throttle.attempted("1.2.3.4");
        throttle.check("5.6.7.8");
    }

    @Test
    @DisplayName("Redis 가 죽으면 막지 않는다 — 가입이 멈추면 안 된다")
    void redisDownDoesNotBlock() {
        SignupThrottle throttle = new SignupThrottle(new BrokenCounter(), 1);
        for (int i = 0; i < 5; i++) {
            throttle.check("1.2.3.4");
            throttle.attempted("1.2.3.4");
        }
    }

    @Test
    @DisplayName("저장 키에 접속 주소가 그대로 남지 않는다 — 해시만 담는다")
    void keyIsHashed() {
        MemoryCounter counter = new MemoryCounter();
        new SignupThrottle(counter, 3).attempted("203.0.113.9");
        assertThat(counter.map.keySet()).singleElement().satisfies(k -> {
            assertThat(k).startsWith("railfeas:signup:");
            assertThat(k).doesNotContain("203.0.113.9");
        });
    }

    @Test
    @DisplayName("가입 API 가 제한을 쓴다 — 중복 이메일(409)도 시도로 세어 가입 여부를 캐는 속도를 막는다")
    void controllerCountsEveryAttemptIncludingDuplicates() {
        AuthService service = mock(AuthService.class);
        when(service.signup(any())).thenThrow(new ApiException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"));
        AuthController controller = new AuthController(service, mock(UserRepository.class), null,
                new LoginThrottle(new MemoryCounter()), new SignupThrottle(new MemoryCounter(), 2));
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr("1.2.3.4");
        AuthDto.SignupRequest request = new AuthDto.SignupRequest("a@b.test", "password123", "n");

        assertThat(statusOf(() -> controller.signup(request, http))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(() -> controller.signup(request, http))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(() -> controller.signup(request, http))).as("두 번 시도한 뒤엔 409 가 아니라 429").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
