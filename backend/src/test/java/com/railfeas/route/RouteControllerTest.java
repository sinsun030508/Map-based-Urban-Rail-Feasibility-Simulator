package com.railfeas.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.calc.MountainCost;
import com.railfeas.calc.Terrain;
import com.railfeas.calc.WaterBodies;
import com.railfeas.calc.WaterDetour;
import com.railfeas.common.ApiException;
import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.CostStandardRepository;
import com.railfeas.reference.ModeCapacityRepository;
import com.railfeas.scenario.ScenarioDto;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.mockito.Mockito;
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
        return controller(Path.of("src/test/resources/dem"));
    }

    /** 지형은 저장소의 추풍령 타일 2장(1752/802·803) — CI 에는 받은 캐시가 없다 */
    private static RouteController controller(Path dem) {
        WaterBodies water = new WaterBodies("../data/build/water_bodies.geojson");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(water, "load");
        CostStandardRepository standards = Mockito.mock(CostStandardRepository.class);
        Mockito.when(standards.findByActiveTrue()).thenReturn(List.of(
                standard(ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 875),
                standard(ModeType.HEAVY_METRO, StructureType.ELEVATED, 550),
                standard(ModeType.BRT_HIGH, StructureType.AT_GRADE, 24)));
        ModeCapacityRepository capacities = Mockito.mock(ModeCapacityRepository.class);
        Mockito.when(capacities.findAll()).thenReturn(List.of());
        return new RouteController(new WaterDetour(water), water, new Terrain(dem), standards, capacities);
    }

    private static CostStandard standard(ModeType mode, StructureType structure, long perKm) {
        try {
            Constructor<CostStandard> c = CostStandard.class.getDeclaredConstructor();
            c.setAccessible(true);
            CostStandard s = c.newInstance();
            for (Object[] kv : new Object[][]{{"modeType", mode}, {"structureType", structure}, {"costPerKm", perKm}}) {
                Field f = CostStandard.class.getDeclaredField((String) kv[0]);
                f.setAccessible(true);
                f.set(s, kv[1]);
            }
            return s;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 추풍령 동쪽 산지를 남북으로 — 테스트 타일 안 */
    private static final List<ScenarioDto.PointRequest> CHUPUNG = List.of(p(36.29, 128.05), p(36.06, 128.05));

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

    @Test
    @DisplayName("지형 자료가 없으면 산 판정이 꺼진다 — 오류 없이 available false")
    void 지형이_없으면_꺼진다() {
        var res = controller(Path.of("src/test/resources/no-such-dem"))
                .mountain(new RouteController.DetourRequest(CHUPUNG, StructureType.ELEVATED));

        assertThat(res.available()).isFalse();
        assertThat(res.mountains()).isEmpty();
        assertThat(res.deltas()).isEmpty();
        assertThat(res.points()).hasSize(2);
    }

    @Test
    @DisplayName("지하는 이미 땅속이다 — 산 구간을 안내하지 않는다")
    void 지하면_안내하지_않는다() {
        var res = controller().mountain(new RouteController.DetourRequest(CHUPUNG, StructureType.UNDERGROUND));

        assertThat(res.available()).isTrue();
        assertThat(res.mountains()).isEmpty();
        assertThat(res.deltas()).isEmpty();
    }

    @Test
    @DisplayName("고가로 산지를 지나면 산 구간과 비용 차이를 준다 — 토막은 노선 둘레, BRT 는 빠진다")
    void 고가는_산_구간을_준다() {
        var res = controller().mountain(new RouteController.DetourRequest(CHUPUNG, StructureType.ELEVATED));

        assertThat(res.available()).isTrue();
        assertThat(res.mountains()).isNotEmpty();
        assertThat(res.mountains().get(0).index()).isEqualTo(1);
        for (var m : res.mountains()) {
            for (var piece : m.pieces()) {
                for (double[] pt : piece) {
                    // {위도, 경도} — 뒤바뀌면 128°N·36°E 가 된다
                    assertThat(pt[0]).isBetween(36.0, 36.35);
                    assertThat(pt[1]).isBetween(127.9, 128.2);
                }
            }
        }
        assertThat(res.deltas()).extracting(MountainCost.Delta::mode).containsExactly(ModeType.HEAVY_METRO);
        assertThat(res.deltas().get(0).tunnelExtraCost()).isPositive();
    }

    @Test
    @DisplayName("1,000km 를 넘는 선은 받지 않는다 — 저장·계산과 같은 상한")
    void 너무_긴_선은_400() {
        var far = List.of(p(37.5, 127.0), p(33.0, 140.0));   // 약 1,250km
        Assertions.assertThatThrownBy(() -> controller().mountain(new RouteController.DetourRequest(far, StructureType.ELEVATED)))
                .isInstanceOf(ApiException.class);
    }
}
