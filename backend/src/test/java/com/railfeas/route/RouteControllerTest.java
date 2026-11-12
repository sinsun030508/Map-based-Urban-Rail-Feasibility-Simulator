package com.railfeas.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.calc.MountainCost;
import com.railfeas.calc.MountainDetour;
import com.railfeas.calc.MountainPlans;
import com.railfeas.calc.PlanStore;
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
        return controller(dem, List.of(
                standard(ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 875),
                standard(ModeType.HEAVY_METRO, StructureType.ELEVATED, 550),
                standard(ModeType.BRT_HIGH, StructureType.AT_GRADE, 24)));
    }

    private static RouteController controller(Path dem, List<CostStandard> active) {
        WaterBodies water = new WaterBodies("../data/build/water_bodies.geojson");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(water, "load");
        CostStandardRepository standards = Mockito.mock(CostStandardRepository.class);
        Mockito.when(standards.findByActiveTrue()).thenReturn(active);
        ModeCapacityRepository capacities = Mockito.mock(ModeCapacityRepository.class);
        Mockito.when(capacities.findAll()).thenReturn(List.of());
        // 캐시는 비어 있는 것으로 — 이 테스트는 판정을 본다
        PlanStore empty = new PlanStore() {
            @Override
            public java.util.Optional<String> get(String key) {
                return java.util.Optional.empty();
            }

            @Override
            public void put(String key, String value, java.time.Duration ttl) {
            }
        };
        return new RouteController(new WaterDetour(water), water, new Terrain(dem), standards, capacities,
                new MountainPlans(empty, new com.fasterxml.jackson.databind.ObjectMapper()));
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
        assertThat(res.modePlans()).isEmpty();
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
        // BRT 도 도로 기울기(7%)로 잰 터널로 비용이 나온다(11-09). 도로는 더 가파르게 올라 터널이 철도보다 길 수 없다
        assertThat(res.deltas()).extracting(MountainCost.Delta::mode).containsExactly(ModeType.HEAVY_METRO, ModeType.BRT_HIGH);
        assertThat(res.deltas().get(0).tunnelExtraCost()).isPositive();
        double rail = res.mountains().stream().mapToDouble(MountainDetour.Mountain::tunnelKm).sum();
        assertThat(res.roadTunnelKm()).isNotNull().isBetween(0.0, rail);
    }

    @Test
    @DisplayName("1,000km 를 넘는 선은 받지 않는다 — 저장·계산과 같은 상한")
    void 너무_긴_선은_400() {
        var far = List.of(p(37.5, 127.0), p(33.0, 140.0));   // 약 1,250km
        Assertions.assertThatThrownBy(() -> controller().mountain(new RouteController.DetourRequest(far, StructureType.ELEVATED)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("단가로 손익을 따진다 — 손해인 곳은 뚫고 이득인 곳은 돈다(트램 손익분기 약 1.08)")
    void 손해인_우회는_빠진다() {
        // 트램(터널 추가분 ÷ km단가 약 1.08)의 따로 안으로 보면 아래 두 경우가 갈린다
        RouteController c = controller(Path.of("src/test/resources/dem"),
                List.of(standard(ModeType.TRAM, StructureType.AT_GRADE, 273)));

        // 터널 0.3km 를 피하려 1.06km 를 돈다(비율 3.5) — 뚫는다. 점은 그대로다
        var lossy = c.mountain(new RouteController.DetourRequest(
                List.of(p(36.14, 128.04), p(36.14, 128.09)), StructureType.ELEVATED)).modePlans().get(0);
        assertThat(lossy.mountains()).hasSize(1);
        assertThat(lossy.mountains().get(0).costlier()).isTrue();
        assertThat(lossy.mountains().get(0).detoured()).isFalse();
        assertThat(lossy.points()).hasSize(2);

        // 터널 0.6km 를 0.15km 만 돌면 된다(비율 0.26) — 돈다
        var worth = c.mountain(new RouteController.DetourRequest(
                List.of(p(36.14, 128.03), p(36.14, 128.05)), StructureType.ELEVATED)).modePlans().get(0);
        assertThat(worth.mountains()).hasSize(1);
        assertThat(worth.mountains().get(0).detoured()).isTrue();
        assertThat(worth.points().size()).isGreaterThan(2);

        // 지하철 고가는 KDI 로 산악 터널이 교량과 km당 비슷해(추가분 약 2억/km) 같은 곳도 뚫는 편이 싸다
        var metro = controller().mountain(new RouteController.DetourRequest(
                List.of(p(36.14, 128.03), p(36.14, 128.05)), StructureType.ELEVATED));
        assertThat(metro.mountains().get(0).detoured()).isFalse();
        assertThat(metro.mountains().get(0).costlier()).isTrue();
    }

    @Test
    @DisplayName("트램은 따로 따진다 — 철도가 뚫는 곳도 트램은 돌아가는 안을 받는다(11-12)")
    void 트램은_따로_돈다() {
        // 공통 안은 철도 행(지하철 고가 — 추가분 약 2억/km)만으로 정한다 — 트램(약 1.08)이 끼면 중앙값이 0.54 로 올라
        // 지하철까지 돌았다. 트램은 같은 산을 돌아야 싸다
        RouteController c = controller(Path.of("src/test/resources/dem"), List.of(
                standard(ModeType.HEAVY_METRO, StructureType.ELEVATED, 550),
                standard(ModeType.TRAM, StructureType.AT_GRADE, 273)));
        var res = c.mountain(new RouteController.DetourRequest(
                List.of(p(36.14, 128.03), p(36.14, 128.05)), StructureType.ELEVATED));

        assertThat(res.mountains().get(0).detoured()).isFalse();
        assertThat(res.modePlans()).extracting(RouteController.ModePlan::mode).containsExactly(ModeType.TRAM);
        var tram = res.modePlans().get(0);
        assertThat(tram.mountains().get(0).detoured()).isTrue();
        assertThat(tram.points().size()).isGreaterThan(2);
        // 트램 행의 두 안 — 뚫는 비용(표에 든 값)은 같은 산이라 공통 안과 같고, 돌아가는 비용이 생긴다
        var d = tram.deltas().stream().filter(x -> x.mode() == ModeType.TRAM).findFirst().orElseThrow();
        var shared = res.deltas().stream().filter(x -> x.mode() == ModeType.TRAM).findFirst().orElseThrow();
        assertThat(d.tunnelExtraCost()).isEqualTo(shared.tunnelExtraCost()).isPositive();
        assertThat(d.detourExtraCost()).isNotNull().isLessThan(d.tunnelExtraCost());

        // 트램 단가가 없으면 따로 줄 것이 없다
        assertThat(controller().mountain(new RouteController.DetourRequest(
                List.of(p(36.14, 128.03), p(36.14, 128.05)), StructureType.ELEVATED)).modePlans()).isEmpty();
    }

    @Test
    @DisplayName("호수 우회도 같은 상한이다 — 한쪽에만 걸면 긴 선이 그쪽으로 들어온다")
    void 호수_우회도_너무_긴_선은_400() {
        var far = List.of(p(37.5, 127.0), p(33.0, 140.0));   // 약 1,250km
        Assertions.assertThatThrownBy(() -> controller().detour(new RouteController.DetourRequest(far, StructureType.ELEVATED)))
                .isInstanceOf(ApiException.class);
    }
}
