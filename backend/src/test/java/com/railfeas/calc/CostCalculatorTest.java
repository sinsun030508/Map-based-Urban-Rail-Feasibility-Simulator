package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import com.railfeas.reference.BenefitParameter;
import com.railfeas.reference.BenefitParameterRepository;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.ModeCapacity;
import com.railfeas.scenario.Scenario;
import com.railfeas.scenario.ScenarioResult;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 수단 비교의 판정 규칙을 고정한다 — 경고 문구, 탈락 조건, 추천.
 *
 * 이 규칙들은 "무엇을 경고하고 무엇을 막을지"라서 조용히 바뀌면 사용자가 받는 결론이
 * 달라진다. 특히 **경고는 계산을 막지 않고, 유일한 탈락 조건은 수송능력 초과**라는
 * 약속이 깨지기 쉬워 그 경계를 박아 둔다.
 *
 * 기준값 엔티티는 시드가 넣는 읽기 전용이라 생성자가 없다. 테스트에서만 리플렉션으로 만든다.
 */
class CostCalculatorTest {

    /** 서울시청에서 남쪽으로 약 10km */
    private static final List<double[]> ROUTE_POINTS = List.of(
            new double[]{37.5665, 126.9780}, new double[]{37.4765, 126.9780});

    /** 원단위는 S7 과 같은 값으로 채운다 — 시드 없이 계산기만 떼어 보기 위해서다 */
    private static final Map<String, Double> PARAMETERS = Map.ofEntries(
            Map.entry("baseline_speed_kmh", 21.3),
            Map.entry("time_value_won_per_hour", 7231.0),
            Map.entry("analysis_years", 40.0),
            Map.entry("construction_years", 5.0),
            Map.entry("discount_rate", 0.045),
            Map.entry("discount_rate_late", 0.035),
            Map.entry("discount_switch_year", 30.0),
            Map.entry("peak_hour_ratio", 0.1159),
            Map.entry("weekday_factor", 1.1041),
            Map.entry("peak_direction_coef", 10.06),
            Map.entry("peak_direction_max", 0.395));

    private CostCalculator calculator() {
        PopulationIndex index = new PopulationIndex("없는파일.csv", "없는파일.csv");
        index.load();

        BenefitParameterRepository parameters = Mockito.mock(BenefitParameterRepository.class);
        Mockito.when(parameters.findAll()).thenReturn(PARAMETERS.entrySet().stream()
                .map(e -> newInstance(BenefitParameter.class,
                        "paramName", e.getKey(),
                        "value", BigDecimal.valueOf(e.getValue())))
                .toList());

        // 역 좌표 파일을 주지 않으면 기존 노선 안내는 비활성 — 계산기만 떼어 본다
        ExistingLineFinder existingLines = new ExistingLineFinder("없는파일.csv", "없는지방파일.csv");
        existingLines.load();
        IntercityRailFinder intercity = new IntercityRailFinder("없는KTX파일.csv");
        intercity.load();

        return new CostCalculator(index, new BenefitCalculator(parameters),
                new StationPlanner(index), new ExistingOptions(existingLines, intercity, new KtxTravelTime("없는파일.csv")));
    }

    private static <T> T newInstance(Class<T> type, Object... nameValues) {
        try {
            Constructor<T> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            T instance = c.newInstance();
            for (int i = 0; i < nameValues.length; i += 2) {
                Field f = type.getDeclaredField((String) nameValues[i]);
                f.setAccessible(true);
                f.set(instance, nameValues[i + 1]);
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type + " 를 만들지 못했습니다", e);
        }
    }

    private Scenario scenario(double lengthKm) {
        Scenario s = Scenario.builder()
                .title("테스트")
                .totalLengthKm(BigDecimal.valueOf(lengthKm))
                .preferredStructure(StructureType.ELEVATED)
                .build();
        s.replacePoints(ROUTE_POINTS.stream()
                .map(p -> newInstance(com.railfeas.scenario.RoutePoint.class,
                        "latitude", BigDecimal.valueOf(p[0]),
                        "longitude", BigDecimal.valueOf(p[1])))
                .toList(), BigDecimal.valueOf(lengthKm));
        return s;
    }

    private CostStandard standard(ModeType mode, StructureType structure) {
        return newInstance(CostStandard.class,
                "id", 1L, "modeType", mode, "structureType", structure,
                "fixedCost", 1_000L, "costPerKm", 500L, "costPerStation", 200L,
                "active", true);
    }

    private ModeCapacity capacity(ModeType mode, Integer min, Integer max,
                                  Double spacing, Double speed, Double maxLength) {
        return newInstance(ModeCapacity.class,
                "modeType", mode, "displayName", mode.name(),
                "pphpdMin", min, "pphpdMax", max,
                "spacingKm", spacing == null ? null : BigDecimal.valueOf(spacing),
                "speedKmh", speed == null ? null : BigDecimal.valueOf(speed),
                "maxLengthKm", maxLength == null ? null : BigDecimal.valueOf(maxLength));
    }

    private CostCalculator.Outcome run(Scenario scenario, ModeCapacity capacity) {
        return calculator().calculate(scenario,
                List.of(standard(capacity.getModeType(), StructureType.ELEVATED)),
                Map.of(capacity.getModeType(), capacity),
                List.of());
    }

    @Test
    @DisplayName("건설비는 고정비 + 연장×단가 + 역수×역당단가다")
    void costIsFixedPlusLengthPlusStations() {
        // 10km, 역간격 2km → 역 5개. 1,000 + 10×500 + 5×200 = 7,000
        var outcome = run(scenario(10.0),
                capacity(ModeType.LIGHT_RAIL, null, 60_000, 2.0, 30.0, null));

        ScenarioResult r = outcome.results().get(0);
        assertThat(r.getStationCount()).isEqualTo(5);
        assertThat(r.getTotalCost()).isEqualTo(7_000L);
    }

    @Test
    @DisplayName("인구 자료가 없으면 수요를 비우고 경고로 알린다 — 0 으로 채우지 않는다")
    void withoutPopulationDemandIsNotInvented() {
        var outcome = run(scenario(10.0),
                capacity(ModeType.LIGHT_RAIL, null, 60_000, 2.0, 30.0, null));

        ScenarioResult r = outcome.results().get(0);
        assertThat(outcome.population1km()).isZero();
        assertThat(r.getEstimatedRidership()).isNull();
        assertThat(r.getBcRatio()).isNull();
        assertThat(r.getWarning()).contains("인구 자료 없음");
        // 수요를 모르면 추천하지 않는다
        assertThat(outcome.recommendedMode()).isNull();
    }

    @Test
    @DisplayName("권장 연장을 넘어도 탈락시키지 않고 경고만 붙인다")
    void overLengthOnlyWarns() {
        var outcome = run(scenario(40.0),
                capacity(ModeType.HEAVY_METRO, null, 80_000, 1.06, 32.4, 35.0));

        ScenarioResult r = outcome.results().get(0);
        assertThat(r.getWarning()).contains("권장 연장 초과");
        assertThat(r.getFeasible()).isTrue();     // 경고는 계산을 막지 않는다
    }

    @Test
    @DisplayName("역간격을 모르면 종점 2개만 둔다")
    void unknownSpacingLeavesTerminalsOnly() {
        var outcome = run(scenario(10.0),
                capacity(ModeType.UPGRADE, null, 30_000, null, null, null));

        ScenarioResult r = outcome.results().get(0);
        assertThat(r.getStationCount()).isEqualTo(2);
        assertThat(r.getTravelTimeMin()).isNull();   // 속도를 모르면 소요시간도 없다
    }

    @Test
    @DisplayName("규제 구역 반경 안을 지나면 '확인 필요'로만 안내한다")
    void restrictedZoneOnlyAsksToCheck() {
        var zone = newInstance(com.railfeas.reference.RestrictedZone.class,
                "name", "테스트 보호구역",
                "centerLat", BigDecimal.valueOf(37.5665),
                "centerLng", BigDecimal.valueOf(126.9780),
                "radiusKm", BigDecimal.valueOf(1.0));
        var capacity = capacity(ModeType.LIGHT_RAIL, null, 60_000, 2.0, 30.0, null);

        var outcome = calculator().calculate(scenario(10.0),
                List.of(standard(ModeType.LIGHT_RAIL, StructureType.ELEVATED)),
                Map.of(ModeType.LIGHT_RAIL, capacity), List.of(zone));

        ScenarioResult r = outcome.results().get(0);
        assertThat(r.getWarning()).contains("테스트 보호구역").contains("확인 필요");
        assertThat(r.getFeasible()).isTrue();     // BLOCKED 라도 계산은 막지 않는다
    }

    @Test
    @DisplayName("정차역은 결과마다 붙고 종점 수를 포함한다")
    void stationsAreAttachedToEachResult() {
        var outcome = run(scenario(10.0),
                capacity(ModeType.LIGHT_RAIL, null, 60_000, 2.0, 30.0, null));

        ScenarioResult r = outcome.results().get(0);
        assertThat(r.getStations()).hasSize(r.getStationCount());
        assertThat(r.getStations().get(0).getSequence()).isZero();
        // 인구를 모르면 역별 이용객도 비워 둔다
        assertThat(r.getStations()).allSatisfy(
                s -> assertThat(s.getEstimatedDailyUsers()).isNull());
    }

    // =========================================================
    // 복선전철 / 광역급행 — 같은 노선에 역간격만 다르게 주면
    // =========================================================
    //
    // 복선전철의 역간격 분포가 쌍봉(급행 6.92km / 일반 2.62km)인데 중앙값 4.30km 를
    // 쓰고 있었다. 가운데는 아무 노선도 없는 틈이라 어느 집단도 설명하지 못했다.
    // **비용 계수는 같다** — 잔차로 가를 근거를 못 봤다(t=+0.12, 검출 한계 1.50배).
    // 그래서 두 수단의 차이는 오직 역간격에서 나와야 한다.

    @Test
    @DisplayName("급행은 역간격이 넓어 역이 적고, 그래서 더 싸다")
    void expressHasFewerStationsAndCostsLess() {
        var normal = run(scenario(30.0),
                capacity(ModeType.DOUBLE_ELEC, null, 60_000, 2.62, 47.6, null));
        var express = run(scenario(30.0),
                capacity(ModeType.EXPRESS_RAIL, null, 60_000, 6.92, 91.8, null));

        var n = normal.results().get(0);
        var e = express.results().get(0);

        // 30 / 2.62 = 11.5 → 11역,  30 / 6.92 = 4.3 → 4역
        assertThat(n.getStationCount()).isEqualTo(11);
        assertThat(e.getStationCount()).isEqualTo(4);

        // 비용 계수가 같으니(standard() 가 같은 값을 준다) 차이는 역수뿐이다
        assertThat(e.getTotalCost()).isLessThan(n.getTotalCost());
        assertThat(n.getTotalCost() - e.getTotalCost())
                .isEqualTo(7L * 200L);   // 역 7개 차이 × 역당 200
    }

    @Test
    @DisplayName("급행은 빨라서 통행시간이 짧다 — 편익이 거기서 나온다")
    void expressIsFasterSoTravelTimeIsShorter() {
        var normal = run(scenario(30.0),
                capacity(ModeType.DOUBLE_ELEC, null, 60_000, 2.62, 47.6, null));
        var express = run(scenario(30.0),
                capacity(ModeType.EXPRESS_RAIL, null, 60_000, 6.92, 91.8, null));

        var n = normal.results().get(0);
        var e = express.results().get(0);
        assertThat(e.getTravelTimeMin()).isLessThan(n.getTravelTimeMin());

        // 91.8 / 47.6 = 1.93 배 빠르다 → 시간은 그 역수만큼. 역을 옆으로 옮긴
        // 만큼 연장이 달라질 수 있어 比가 정확히 1.93 은 아니다 — 범위로 박는다
        double ratio = n.getTravelTimeMin().doubleValue() / e.getTravelTimeMin().doubleValue();
        assertThat(ratio).isBetween(1.8, 2.1);
    }

    @Test
    @DisplayName("같은 역간격이면 두 수단은 똑같다 — 수단 이름이 값을 바꾸지 않는다")
    void modeNameAloneChangesNothing() {
        // **이것이 "비용을 가르지 않았다"의 뜻이다.** 급행이라는 이름 자체는
        // 아무 효과가 없고, 역간격과 속도만이 결과를 가른다. 나중에 누가
        // 수단 이름으로 분기를 넣으면 이 테스트가 깨진다.
        var a = run(scenario(30.0),
                capacity(ModeType.DOUBLE_ELEC, null, 60_000, 4.0, 60.0, null));
        var b = run(scenario(30.0),
                capacity(ModeType.EXPRESS_RAIL, null, 60_000, 4.0, 60.0, null));

        assertThat(b.results().get(0).getTotalCost())
                .isEqualTo(a.results().get(0).getTotalCost());
        assertThat(b.results().get(0).getStationCount())
                .isEqualTo(a.results().get(0).getStationCount());
        assertThat(b.results().get(0).getTravelTimeMin())
                .isEqualTo(a.results().get(0).getTravelTimeMin());
    }

    @Test
    @DisplayName("역간격보다 짧은 노선도 출발·도착 두 역은 놓는다")
    void shortRouteStillGetsTwoStations() {
        // 급행 역간격 6.92km 는 지금까지 중 가장 넓다 — round(L/6.92) < 2 가 되는
        // **L < 10.4km 노선이 전부 이 가장자리에 걸린다.** 사용자가 흔히 그리는 길이다.
        // 묶지 않으면 역 1개(또는 0개)로 배치가 깨지고 역당단가도 어긋난다.
        var outcome = run(scenario(5.0),
                capacity(ModeType.EXPRESS_RAIL, null, 60_000, 6.92, 91.8, null));
        var r = outcome.results().get(0);
        assertThat(r.getStationCount()).isEqualTo(2);
        assertThat(r.getStations()).hasSize(2);

        // 1,000 + 5x500 + 2x200 = 3,900
        assertThat(r.getTotalCost()).isEqualTo(3_900L);
    }

    @Test
    @DisplayName("역간격이 없는 수단도 두 역은 놓는다")
    void missingSpacingStillGetsTwoStations() {
        // 단선전철·기존선 개량은 역간격이 NULL 이다. 비교표에는 안 나오지만
        // 기준값이 바뀌어 들어오면 0 으로 나누지 않고 최소값으로 떨어져야 한다.
        var outcome = run(scenario(20.0),
                capacity(ModeType.SINGLE_ELEC, null, 25_000, null, 45.0, null));
        assertThat(outcome.results().get(0).getStationCount()).isEqualTo(2);
    }

    private ScenarioResult result(ModeType mode, double bc) {
        return newInstance(ScenarioResult.class, "modeType", mode,
                "feasible", Boolean.TRUE, "bcRatio", BigDecimal.valueOf(bc));
    }

    /**
     * **권장 연장을 넘는 수단은 추천하지 않는다.** 서울~부산(340km)에서 340km 짜리
     * BRT 고급형이 B/C 7.83 으로 1위에 올라왔다. 표에는 남기되 기본으로 내세우지 않는다 —
     * 화면이 기본으로 그리는 안과 **같은 규칙**이어야 한다(`App.jsx` 의 `overLength`).
     */
    @Test
    @DisplayName("권장 연장을 넘는 수단은 B/C 가 가장 높아도 추천하지 않는다")
    void doesNotRecommendModesOverTheirLengthLimit() {
        var results = List.of(result(ModeType.BRT_HIGH, 7.83),       // 상한 30km — 넘는다
                result(ModeType.DOUBLE_ELEC, 1.77));                 // 상한 없음
        var capacities = Map.of(
                ModeType.BRT_HIGH, capacity(ModeType.BRT_HIGH, null, 35_000, 1.16, 25.0, 30.0),
                ModeType.DOUBLE_ELEC, capacity(ModeType.DOUBLE_ELEC, null, 60_000, 2.62, 47.6, null));

        assertThat(CostCalculator.recommended(results, capacities, 340.5))
                .isEqualTo(ModeType.DOUBLE_ELEC);
        // 상한 안이면 그대로 B/C 최고안이다
        assertThat(CostCalculator.recommended(results, capacities, 20.0))
                .isEqualTo(ModeType.BRT_HIGH);
    }

    @Test
    @DisplayName("전부 상한을 넘으면 길이를 따지지 않는다 — 추천이 사라지는 것보다 낫다")
    void fallsBackWhenEveryModeIsOverTheLimit() {
        var results = List.of(result(ModeType.BRT_HIGH, 7.83));
        var capacities = Map.of(
                ModeType.BRT_HIGH, capacity(ModeType.BRT_HIGH, null, 35_000, 1.16, 25.0, 30.0));

        assertThat(CostCalculator.recommended(results, capacities, 340.5))
                .isEqualTo(ModeType.BRT_HIGH);
    }

    /**
     * **절감이 없는 안은 추천하지 않는다.** 기존 KTX 를 편익 기준에 넣자 서울~부산의 신설안
     * 11개가 전부 B/C 0 이 됐는데, 추천 칸에는 여전히 복선전철이 찍혔다 — 아무 시간도
     * 아끼지 못하는 안을 내세운 셈이다. 답은 "추천할 신설안이 없다" 다.
     */
    @Test
    @DisplayName("B/C 가 0 인 안은 추천하지 않는다 — 전부 0 이면 추천이 없다")
    void doesNotRecommendZeroBenefit() {
        var capacities = Map.of(
                ModeType.DOUBLE_ELEC, capacity(ModeType.DOUBLE_ELEC, null, 60_000, 2.62, 47.6, null),
                ModeType.EXPRESS_RAIL, capacity(ModeType.EXPRESS_RAIL, null, 60_000, 6.92, 91.8, null));

        assertThat(CostCalculator.recommended(List.of(
                result(ModeType.DOUBLE_ELEC, 0.0), result(ModeType.EXPRESS_RAIL, 0.0)),
                capacities, 340.5)).isNull();
        // 하나라도 절감이 있으면 그 안이다
        assertThat(CostCalculator.recommended(List.of(
                result(ModeType.DOUBLE_ELEC, 0.0), result(ModeType.EXPRESS_RAIL, 0.4)),
                capacities, 340.5)).isEqualTo(ModeType.EXPRESS_RAIL);
    }
}
