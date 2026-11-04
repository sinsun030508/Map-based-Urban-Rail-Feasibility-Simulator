package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.common.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * **노선 전체 길이에 상한이 있다** ({@link ScenarioService#MAX_ROUTE_KM}).
 *
 * <p>점 개수(50)와 좌표 범위는 막혀 있었지만 길이는 아니었다. 부하 점검(11-04)에서 점 50개로 전국을
 * 지그재그로 그리니 18,528km 가 저장됐고, 계산 한 번에 **137.8초**, 응답 14MB, 정차역 11만 7천 개가
 * DB 에 들어갔다 — 로그인한 누구나 서버를 2분 넘게 묶을 수 있었다.
 */
class RouteLengthLimitTest {

    private static ScenarioDto.PointRequest p(double lat, double lng) {
        return new ScenarioDto.PointRequest(BigDecimal.valueOf(lat), BigDecimal.valueOf(lng));
    }

    @Test
    @DisplayName("서울~부산(약 330km)은 그대로 잰다")
    void seoulBusanPasses() {
        BigDecimal km = ScenarioService.lengthOf(List.of(p(37.5663, 126.9779), p(35.1798, 129.0750)));
        assertThat(km.doubleValue()).isBetween(300.0, 350.0);
    }

    @Test
    @DisplayName("상한을 넘는 노선은 400 으로 거절하고 이유를 말한다")
    void tooLongIsRejected() {
        // 서울~부산을 네 번 오간다 — 약 1,300km
        var zigzag = List.of(p(37.5663, 126.9779), p(35.1798, 129.0750), p(37.5663, 126.9779),
                p(35.1798, 129.0750), p(37.5663, 126.9779));

        assertThatThrownBy(() -> ScenarioService.lengthOf(zigzag))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("1,000km");
    }
}
