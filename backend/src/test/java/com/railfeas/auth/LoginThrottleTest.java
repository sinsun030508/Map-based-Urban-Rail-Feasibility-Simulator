package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.common.ApiException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 비밀번호 무차별 대입을 막는다. 회원이 아니라 **(이메일, 접속 주소)** 를 묶는다 — 이메일만 묶으면 남의 주소로 틀려 주는 것만으로
 * 그 사람을 로그인 못 하게 만들 수 있다. **Redis 가 죽으면 막지 않는다** — 보조 장치가 로그인을 멈추면 안 된다.
 */
class LoginThrottleTest {

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

    private final MemoryCounter counter = new MemoryCounter();
    private final LoginThrottle throttle = new LoginThrottle(counter);

    private void fail(String email, String ip, int times) {
        for (int i = 0; i < times; i++) {
            throttle.failed(email, ip);
        }
    }

    @Test
    @DisplayName("한도 전에는 막지 않는다")
    void 한도_전() {
        fail("a@example.test", "1.1.1.1", LoginThrottle.MAX_FAILURES - 1);

        assertThatCode(() -> throttle.check("a@example.test", "1.1.1.1")).doesNotThrowAnyException();
        assertThat(counter.window).isPositive();
    }

    @Test
    @DisplayName("한도에 닿으면 429 다 — 맞는 비밀번호도 기다려야 한다")
    void 한도() {
        fail("a@example.test", "1.1.1.1", LoginThrottle.MAX_FAILURES);

        assertThatThrownBy(() -> throttle.check("a@example.test", "1.1.1.1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("다른 접속 주소의 같은 이메일은 막히지 않는다 — 남을 잠글 수 없다")
    void 다른_주소() {
        fail("a@example.test", "1.1.1.1", LoginThrottle.MAX_FAILURES);

        assertThatCode(() -> throttle.check("a@example.test", "2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이메일 대소문자로 한도를 비켜 가지 못한다")
    void 대소문자() {
        fail("A@Example.Test", "1.1.1.1", LoginThrottle.MAX_FAILURES);

        assertThatThrownBy(() -> throttle.check("a@example.test", "1.1.1.1")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("로그인에 성공하면 센 것을 지운다")
    void 성공() {
        fail("a@example.test", "1.1.1.1", LoginThrottle.MAX_FAILURES - 1);

        throttle.succeeded("a@example.test", "1.1.1.1");
        fail("a@example.test", "1.1.1.1", 1);

        assertThatCode(() -> throttle.check("a@example.test", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 가 죽어도 로그인은 된다")
    void 저장소가_죽어도_된다() {
        LoginThrottle broken = new LoginThrottle(new BrokenCounter());

        assertThatCode(() -> {
            broken.check("a@example.test", "1.1.1.1");
            broken.failed("a@example.test", "1.1.1.1");
            broken.succeeded("a@example.test", "1.1.1.1");
        }).doesNotThrowAnyException();
    }

    private final AuthService service = Mockito.mock(AuthService.class);
    private final AuthController controller = new AuthController(service, null, null, throttle);
    private final AuthDto.LoginRequest request = new AuthDto.LoginRequest("a@example.test", "틀린 값");

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr(address);
        return http;
    }

    @Test
    @DisplayName("로그인이 401 이면 세고, 한도면 비밀번호를 확인하기 전에 막는다")
    void 컨트롤러가_센다() {
        Mockito.when(service.login(request)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "틀림"));
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertThatThrownBy(() -> controller.login(request, from("1.1.1.1"))).isInstanceOf(ApiException.class);
        }
        Mockito.clearInvocations(service);

        assertThatThrownBy(() -> controller.login(request, from("1.1.1.1")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        Mockito.verifyNoInteractions(service);
    }

    @Test
    @DisplayName("로그인에 성공하면 컨트롤러가 센 것을 지운다")
    void 컨트롤러가_지운다() {
        fail("a@example.test", "1.1.1.1", 3);
        Mockito.when(service.login(request)).thenReturn(new AuthDto.TokenResponse("토큰", 1L, "닉", "USER"));

        controller.login(request, from("1.1.1.1"));

        assertThat(counter.map).isEmpty();
    }
}
