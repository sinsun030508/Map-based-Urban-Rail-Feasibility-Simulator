package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.common.ModeType;
import com.railfeas.common.PointType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 노선을 고치면 옛 노선으로 낸 값이 **하나도** 남지 않아야 한다.
 *
 * 결과·추천만 지우고 반경 1km 인구와 계산 시각을 남겨 두어, 고친 노선을 다시 계산하기 전까지
 * 시나리오 비교에 **옛 노선의 인구**가 새 노선 이름 옆에 찍혔다(11-05).
 */
class ScenarioTest {

    private static List<RoutePoint> points(double lat) {
        return List.of(
                RoutePoint.builder().sequence(0).pointType(PointType.START)
                        .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(126.978)).build(),
                RoutePoint.builder().sequence(1).pointType(PointType.END)
                        .latitude(BigDecimal.valueOf(lat - 0.09)).longitude(BigDecimal.valueOf(126.978)).build());
    }

    /** 한 번 계산까지 마친 시나리오 — 인구·계산 시각·추천·기존 노선 안내가 모두 차 있다 */
    private static Scenario calculated() {
        Scenario s = Scenario.builder().title("시험").totalLengthKm(BigDecimal.TEN).build();
        s.replacePoints(points(37.5665), BigDecimal.TEN);
        s.applyResults(List.of(), 123_456L, ModeType.LIGHT_RAIL);
        s.noteExistingLine("서울역~사당역");
        assertThat(s.getPopulation1km()).isNotNull();
        assertThat(s.getCalculatedAt()).isNotNull();
        return s;
    }

    private static void assertForgotten(Scenario s) {
        assertThat(s.getPopulation1km()).as("옛 노선의 반경 1km 인구").isNull();
        assertThat(s.getCalculatedAt()).as("옛 노선의 계산 시각").isNull();
        assertThat(s.getRecommendedMode()).as("옛 노선의 추천 수단").isNull();
        assertThat(s.getExistingLine()).as("옛 노선의 기존 노선 안내").isNull();
        assertThat(s.getResults()).isEmpty();
    }

    @Test
    @DisplayName("점을 바꿔 넣으면 옛 노선의 인구·계산 시각을 0 이 아니라 모름으로 돌린다")
    void replacingPointsForgetsTheOldCalculation() {
        Scenario s = calculated();
        s.replacePoints(points(35.1796), BigDecimal.ONE);
        assertForgotten(s);
    }

    @Test
    @DisplayName("수정 저장이 먼저 지나는 clearPoints 도 옛 계산을 남기지 않는다")
    void clearingPointsForgetsTheOldCalculation() {
        Scenario s = calculated();
        s.clearPoints();
        assertForgotten(s);
    }
}
