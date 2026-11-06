package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API 문서는 관리자 경로까지 전부 드러내므로 켰을 때만 연다. 꺼져 있으면 경로를 열 이유가 없다 —
 * 열어 두면 나중에 문서를 켜는 순간 조용히 공개된다.
 */
class SwaggerAccessTest {

    @Test
    @DisplayName("꺼져 있으면 문서 경로를 열지 않는다")
    void 꺼지면_닫는다() {
        assertThat(SecurityConfig.publicDocs(false)).isEmpty();
    }

    @Test
    @DisplayName("켜면 문서와 화면 경로만 연다")
    void 켜면_연다() {
        assertThat(SecurityConfig.publicDocs(true))
                .containsExactlyInAnyOrder("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");
    }
}
