package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

/**
 * 프론트 개발 서버의 주소는 **설정으로 정한다**(`CORS_ORIGINS`, 11-14). 5173 을 코드에 박아 두었더니 윈도우가 그 포트(5094~5193)를
 * 예약한 PC 에서 프론트를 다른 포트로 띄우면 모든 POST 가 「Invalid CORS request」 로 막혔다.
 */
class CorsOriginsTest {

    private static CorsConfiguration configFor(List<String> origins) {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        return SecurityConfig.corsSource(origins).getCorsConfiguration(request);
    }

    @Test
    @DisplayName("쉼표로 나눠 적은 출처를 목록으로 — 앞뒤 공백·빈 칸은 버린다")
    void 목록으로_읽는다() {
        assertThat(SecurityConfig.corsOrigins(" http://localhost:3000 , ,http://localhost:5173,")).containsExactly(
                "http://localhost:3000", "http://localhost:5173");
        assertThat(SecurityConfig.corsOrigins("")).isEmpty();
        assertThat(SecurityConfig.corsOrigins(null)).isEmpty();
    }

    @Test
    @DisplayName("적은 출처만 허용한다 — 5173·3000 은 되고, 적지 않은 포트와 와일드카드는 안 된다")
    void 적은_출처만_허용한다() {
        var config = configFor(SecurityConfig.corsOrigins("http://localhost:3000,http://localhost:5173"));

        assertThat(config.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(config.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(config.checkOrigin("http://localhost:5200")).isNull();          // 적지 않은 포트
        assertThat(config.checkOrigin("https://evil.example")).isNull();
        assertThat(config.getAllowedOrigins()).doesNotContain("*");
    }

    @Test
    @DisplayName("설정을 바꾸면 허용 출처도 바뀐다 — 코드를 고치지 않고 포트를 옮긴다")
    void 설정으로_옮긴다() {
        var config = configFor(SecurityConfig.corsOrigins("http://localhost:5200"));

        assertThat(config.checkOrigin("http://localhost:5200")).isNotNull();
        assertThat(config.checkOrigin("http://localhost:5173")).isNull();
    }
}
