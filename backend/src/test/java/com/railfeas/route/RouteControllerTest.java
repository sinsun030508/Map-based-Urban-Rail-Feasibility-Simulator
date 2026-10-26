package com.railfeas.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.calc.WaterBodies;
import com.railfeas.calc.WaterDetour;
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

    @Test
    @DisplayName("응답의 위도·경도는 요청과 같은 자리에 있다 — 우회점도 노선 둘레다")
    void 위경도_순서() {
        WaterBodies water = new WaterBodies("../data/build/water_bodies.geojson");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(water, "load");
        RouteController controller = new RouteController(new WaterDetour(water));

        var res = controller.detour(new RouteController.DetourRequest(
                List.of(p(37.2391, 127.06), p(37.2391, 127.14))));

        assertThat(res.crossings()).extracting(WaterDetour.Crossing::name).contains("신갈저수지");
        assertThat(res.points().size()).isGreaterThan(2);
        assertThat(res.points().get(0).latitude()).isEqualByComparingTo("37.2391");
        assertThat(res.points().get(0).longitude()).isEqualByComparingTo("127.06");
        // 우회점은 노선 둘레(수 km 안)에 있다 — 위경도가 뒤바뀌면 127°N·37°E 가 된다
        for (var pt : res.points()) {
            assertThat(pt.latitude().doubleValue()).isBetween(37.1, 37.4);
            assertThat(pt.longitude().doubleValue()).isBetween(127.0, 127.2);
        }
    }
}
