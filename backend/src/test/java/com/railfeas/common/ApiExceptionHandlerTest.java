package com.railfeas.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 검증 오류 문구는 **화면에 그대로 뜬다** — 내부 필드 경로가 새면 안 된다.
 *
 * <p>지도를 넓게 보고 제주 남쪽 바다를 살짝 잘못 누르면 점이 위도 33° 아래로 찍히는데, 그때
 * 「points[1].latitude — 다음 값 이상이어야 합니다 33.0」 이 떴다(11-04 화면 점검). 필드 이름 표가
 * `points` 는 알았지만 목록 안의 `points[1].latitude` 는 몰랐다.
 */
class ApiExceptionHandlerTest {

    @Test
    @DisplayName("지점 좌표 오류는 몇 번째 지점이 한반도 밖인지로 말한다")
    void pointOutsideKorea() {
        assertThat(ApiExceptionHandler.message("points[1].latitude", "다음 값 이상이어야 합니다 33.0"))
                .isEqualTo("2번째 지점이 한반도 밖입니다 (위도 33~39°, 경도 124~132°)");
        assertThat(ApiExceptionHandler.message("points[0].longitude", "다음 값 이하여야 합니다 132.0"))
                .startsWith("1번째 지점이");
    }

    @Test
    @DisplayName("단순 필드는 전처럼 우리말 이름 — 기본 문구")
    void plainFieldKeepsOldShape() {
        assertThat(ApiExceptionHandler.message("password", "크기가 8에서 64 사이여야 합니다"))
                .isEqualTo("비밀번호 — 크기가 8에서 64 사이여야 합니다");
    }
}
