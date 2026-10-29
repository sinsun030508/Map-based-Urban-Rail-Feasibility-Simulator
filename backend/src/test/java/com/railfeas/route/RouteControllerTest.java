package com.railfeas.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.calc.WaterBodies;
import com.railfeas.calc.WaterDetour;
import com.railfeas.common.StructureType;
import com.railfeas.scenario.ScenarioDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 우회 제안 API — 내부의 {위도, 경도} 배열을 `latitude`·`longitude` 로 옮긴다. 순서가 바뀌면
 * 오류 없이 **우회선이 엉뚱한 곳(바다 한가운데)에** 그려진다. 실제 호수 파일로 본다.
 */
class RouteControllerTest {

    private static ScenarioDto.PointRequest p(double lat, double lng) {
        return new ScenarioDto.PointRequest(BigDecimal.valueOf(lat), BigDecimal.valueOf(lng));
    }

    private static RouteController controller() {
        WaterBodies water = new WaterBodies("../data/build/water_bodies.geojson");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(water, "load");
        return new RouteController(new WaterDetour(water), water);
    }

    /** 신갈저수지를 동서로 가로지른다 */
    private static final List<ScenarioDto.PointRequest> SINGAL = List.of(p(37.2391, 127.06), p(37.2391, 127.14));

    @Test
    @DisplayName("응답의 위도·경도는 요청과 같은 자리에 있다 — 우회점도 노선 둘레다")
    void 위경도_순서() {
        var res = controller().detour(new RouteController.DetourRequest(SINGAL, StructureType.ELEVATED));

        assertThat(res.crossings()).extracting(WaterDetour.Crossing::name).contains("신갈저수지");
        // 그린 선 그대로 물 위를 지나는 길이도 함께 준다
        assertThat(res.waterKm()).extracting(WaterBodies.Span::name).contains("신갈저수지");
        assertThat(res.points().size()).isGreaterThan(2);
        assertThat(res.points().get(0).latitude()).isEqualByComparingTo("37.2391");
        assertThat(res.points().get(0).longitude()).isEqualByComparingTo("127.06");
        // 우회점은 노선 둘레(수 km 안)에 있다 — 위경도가 뒤바뀌면 127°N·37°E 가 된다
        for (var pt : res.points()) {
            assertThat(pt.latitude().doubleValue()).isBetween(37.1, 37.4);
            assertThat(pt.longitude().doubleValue()).isBetween(127.0, 127.2);
        }
    }

    /**
     * 지하는 물 밑으로 지나가면 된다 — 지하철에 「돌아가는 선형」 을 권하면 틀린 안내다.
     * 물 아래를 지나는 길이(`waterKm`)는 그대로 준다(하저터널 할증도 없다는 것을 알려야 한다).
     */
    @Test
    @DisplayName("지하는 호수를 돌아가라고 하지 않는다 — 고가·지상만 제안한다")
    void 지하는_우회하지_않는다() {
        RouteController c = controller();

        var under = c.detour(new RouteController.DetourRequest(SINGAL, StructureType.UNDERGROUND));
        assertThat(under.crossings()).isEmpty();
        assertThat(under.points()).hasSize(2);
        assertThat(under.waterKm()).extracting(WaterBodies.Span::name).contains("신갈저수지");

        for (StructureType above : List.of(StructureType.ELEVATED, StructureType.AT_GRADE)) {
            assertThat(c.detour(new RouteController.DetourRequest(SINGAL, above)).crossings())
                    .as("%s", above).extracting(WaterDetour.Crossing::name).contains("신갈저수지");
        }
        // 구조를 안 보낸 예전 요청은 지상 쪽으로 본다 — 조용히 제안을 잃지 않게
        assertThat(c.detour(new RouteController.DetourRequest(SINGAL, null)).crossings()).isNotEmpty();
    }
}
