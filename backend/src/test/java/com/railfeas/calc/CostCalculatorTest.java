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
            Map.entry("peak_direction_max", 0.395),
            Map.entry("avg_trip_km", 13.6),
            Map.entry("users_per_ridership", 1.0));

    private CostCalculator calculator() {
        // 역 좌표 파일을 주지 않으면 기존 노선 안내는 비활성 — 계산기만 떼어 본다
        IntercityRailFinder intercity = new IntercityRailFinder("없는KTX파일.csv");
        intercity.load();
        return calculator(intercity);
    }

    private CostCalculator calculator(IntercityRailFinder intercity) {
        return calculator(intercity, TunnelKm.NONE);
    }

    private CostCalculator calculator(IntercityRailFinder intercity, TunnelKm tunnel) {
        return calculator(intercity, tunnel, RoadNetwork.of(List.of()), new WaterBodies("없는파일.geojson"));
    }

    private CostCalculator calculator(RoadNetwork roads, WaterBodies water) {
        IntercityRailFinder intercity = new IntercityRailFinder("없는KTX파일.csv");
        intercity.load();
        return calculator(intercity, TunnelKm.NONE, roads, water);
    }

    /** 해안선까지 받는다 — 열차 찾기는 없는 파일로(KTX 는 이 테스트의 관심이 아니다) */
    private CostCalculator calculator(RoadNetwork roads, WaterBodies water, Coastline coast) {
        IntercityRailFinder intercity = new IntercityRailFinder("없는KTX파일.csv");
        intercity.load();
        return calculator(intercity, TunnelKm.NONE, roads, water, coast);
    }

    private CostCalculator calculator(IntercityRailFinder intercity, TunnelKm tunnel, RoadNetwork roads, WaterBodies water) {
        return calculator(intercity, tunnel, roads, water, null);
    }

    private CostCalculator calculator(IntercityRailFinder intercity, TunnelKm tunnel, RoadNetwork roads, WaterBodies water, Coastline coast) {
        return calculator(intercity, tunnel, roads, water, coast, RoadNetwork.of(List.of()));
    }

    /** 정차역을 옮길 큰길(`stationRoads`)까지 받는다 — 역이 큰길로 옮겨져 그린 선 밖에 서는 노선을 만든다 */
    private CostCalculator calculator(IntercityRailFinder intercity, TunnelKm tunnel, RoadNetwork roads, WaterBodies water, Coastline coast,
                                      RoadNetwork stationRoads) {
        PopulationIndex index = new PopulationIndex("없는파일.csv", "없는파일.csv");
        index.load();

        BenefitParameterRepository parameters = Mockito.mock(BenefitParameterRepository.class);
        Mockito.when(parameters.findAll()).thenReturn(PARAMETERS.entrySet().stream()
                .map(e -> newInstance(BenefitParameter.class,
                        "paramName", e.getKey(),
                        "value", BigDecimal.valueOf(e.getValue())))
                .toList());

        ExistingLineFinder existingLines = new ExistingLineFinder("없는파일.csv", "없는지방파일.csv");
        existingLines.load();

        return new CostCalculator(index, new BenefitCalculator(parameters),
                new StationPlanner(index, stationRoads), new ExistingOptions(existingLines, intercity, new KtxTravelTime("없는파일.csv"),
                        new UrbanLineSpeed("없는파일.csv")), tunnel, roads, water, coast);
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
        return standard(mode, structure, 500L);
    }

    private CostStandard standard(ModeType mode, StructureType structure, long perKm) {
        return newInstance(CostStandard.class,
                "id", 1L, "modeType", mode, "structureType", structure,
                "fixedCost", 1_000L, "costPerKm", perKm, "costPerStation", 200L,
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
    @DisplayName("인구가 없다는 경고는 수도권 한정처럼 말하지 않는다 — 자료는 전국이고, 아는 것은 「선 둘레 1km 안에 집계가 없다」 뿐이다")
    void noPopulationWarningDoesNotBlameTheRegion() {
        // 새만금 간척지처럼 전국 자료 안인데 선 둘레에 집계구가 없는 곳(11-22 훑기에서 「수도권 밖이라」 로 나왔다)
        var outcome = run(scenario(10.0),
                capacity(ModeType.LIGHT_RAIL, null, 60_000, 2.0, 30.0, null));

        String warning = outcome.results().get(0).getWarning();
        assertThat(warning).contains("인구 자료 없음").contains("1km");
        assertThat(warning).as("전국 자료를 받은 뒤라 수도권 밖이라는 설명은 사실이 아니다").doesNotContain("수도권");
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

    /**
     * **KTX 를 찾는 문턱은 권장 연장과 따로다.** 전에는 권장 연장의 최댓값을 문턱으로 써서,
     * 지하철 권장 연장을 실측(60.2km)으로 올리면 40km 노선에서 KTX 안내가 사라졌다 —
     * 같은 KTX 노선의 역이 35~60km 떨어진 구간이 79쌍이다(동대구~경주 등).
     */
    @Test
    @DisplayName("지하철 권장 연장을 60.2km 로 올려도 40km 노선에 기존 KTX 가 붙는다")
    void ktxThresholdIsNotTheLengthCap() {
        IntercityRailFinder intercity = new IntercityRailFinder("없는KTX파일.csv");
        intercity.add("KTX 가상선", "북쪽", ROUTE_POINTS.get(0)[0], ROUTE_POINTS.get(0)[1], false);
        intercity.add("KTX 가상선", "남쪽", ROUTE_POINTS.get(1)[0], ROUTE_POINTS.get(1)[1], false);
        var heavy = capacity(ModeType.HEAVY_METRO, null, 80_000, 1.06, 32.4, 60.2);

        var outcome = calculator(intercity).calculate(scenario(40.0),
                List.of(standard(ModeType.HEAVY_METRO, StructureType.ELEVATED)),
                Map.of(ModeType.HEAVY_METRO, heavy), List.of());

        assertThat(outcome.existingOption())
                .extracting(ExistingOptions.Option::kind)
                .isEqualTo(ExistingOptions.INTERCITY_RAIL);
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
    @DisplayName("도로를 따라가는 행의 구역 경고는 새로 놓는 구간만 본다 — 기존 도로가 비껴가면 직선이 지나도 경고가 없다(11-14)")
    void roadRowsWarnAboutNewRoadOnly() {
        // 노선 10km 한가운데의 반경 1km 구역 — 직선은 지나고, 동쪽 1.76km 를 도는 기존 도로(1.35배)는 비껴간다
        var middle = newInstance(com.railfeas.reference.RestrictedZone.class,
                "name", "가운데 보호구역",
                "centerLat", BigDecimal.valueOf(37.5215),
                "centerLng", BigDecimal.valueOf(126.9780),
                "radiusKm", BigDecimal.valueOf(1.0));
        var capacities = Map.of(
                ModeType.TRAM, capacity(ModeType.TRAM, null, 60_000, 1.0, 20.0, null),
                ModeType.HEAVY_METRO, capacity(ModeType.HEAVY_METRO, null, 80_000, 1.0, 20.0, null));
        var standards = List.of(standard(ModeType.TRAM, StructureType.AT_GRADE), standard(ModeType.HEAVY_METRO, StructureType.ELEVATED));

        var around = calculator(bracketRoad(0.02), new WaterBodies("없는파일.geojson"))
                .calculate(scenario(10.0), standards, capacities, List.of(middle));
        assertThat(String.valueOf(around.results().get(0).getWarning())).as("트램 — 기존 도로가 구역을 비껴간다").doesNotContain("가운데 보호구역");
        assertThat(around.results().get(1).getWarning()).as("지하철 — 그린 직선은 구역을 지난다").contains("가운데 보호구역");

        // 기존 도로가 1.5배를 넘어 새 도로로 두면(동쪽 5.3km) 그 직선 구간은 구역을 지난다 — 경고가 붙는다
        var through = calculator(bracketRoad(0.06), new WaterBodies("없는파일.geojson"))
                .calculate(scenario(10.0), standards, capacities, List.of(middle));
        assertThat(through.results().get(0).getWarning()).as("트램 — 새 도로가 구역을 지난다").contains("가운데 보호구역");
    }

    @Test
    @DisplayName("찍은 점이 아니라 선이 구역을 지나면 안내한다 — 양 끝이 5km 밖이어도(11-12, 경복궁)")
    void restrictedZoneOnTheLineNotJustAtThePoints() {
        // 노선 10km 의 한가운데에 반경 1km 구역 — 양 끝은 5km 떨어져 있다
        var middle = newInstance(com.railfeas.reference.RestrictedZone.class,
                "name", "가운데 보호구역",
                "centerLat", BigDecimal.valueOf(37.5215),
                "centerLng", BigDecimal.valueOf(126.9780),
                "radiusKm", BigDecimal.valueOf(1.0));
        // 선에서 동쪽 1.5km — 반경 밖이라 말하지 않는다
        var beside = newInstance(com.railfeas.reference.RestrictedZone.class,
                "name", "옆 보호구역",
                "centerLat", BigDecimal.valueOf(37.5215),
                "centerLng", BigDecimal.valueOf(126.9950),
                "radiusKm", BigDecimal.valueOf(1.0));
        var capacity = capacity(ModeType.LIGHT_RAIL, null, 60_000, 2.0, 30.0, null);

        var outcome = calculator().calculate(scenario(10.0),
                List.of(standard(ModeType.LIGHT_RAIL, StructureType.ELEVATED)),
                Map.of(ModeType.LIGHT_RAIL, capacity), List.of(beside, middle));

        assertThat(outcome.results().get(0).getWarning()).contains("가운데 보호구역").doesNotContain("옆 보호구역");
    }

    /** 그린 노선(남북 10km) 옆으로 동쪽 eastDeg° 를 돌아가는 기존 도로 — ㄷ자 */
    private static RoadNetwork bracketRoad(double eastDeg) {
        double[] a = ROUTE_POINTS.get(0), b = ROUTE_POINTS.get(1);
        return RoadNetwork.ofEdges(List.of(
                new RoadNetwork.Edge(0, 1, 0, new double[][]{a, {a[0], a[1] + eastDeg}}),
                new RoadNetwork.Edge(1, 2, 0, new double[][]{{a[0], a[1] + eastDeg}, {b[0], b[1] + eastDeg}}),
                new RoadNetwork.Edge(2, 3, 0, new double[][]{{b[0], b[1] + eastDeg}, b})));
    }

    @Test
    @DisplayName("트램·BRT 는 기존 도로를 따라간다 — 1.5배 안이면 도로 길이로 세고 철도 행은 그린 직선 그대로(11-12)")
    void roadModesFollowExistingRoads() {
        // 동쪽 1.76km 를 돌아가는 도로 — 13.5km, 직선 10km 의 1.35배
        CostCalculator calc = calculator(bracketRoad(0.02), new WaterBodies("없는파일.geojson"));
        var outcome = calc.calculate(scenario(10.0),
                List.of(standard(ModeType.TRAM, StructureType.AT_GRADE), standard(ModeType.HEAVY_METRO, StructureType.ELEVATED),
                        standard(ModeType.EXPRESS_RAIL, StructureType.UNDERGROUND)),
                Map.of(ModeType.TRAM, capacity(ModeType.TRAM, null, 60_000, 1.0, 20.0, null),
                        ModeType.HEAVY_METRO, capacity(ModeType.HEAVY_METRO, null, 80_000, 1.0, 20.0, null),
                        ModeType.EXPRESS_RAIL, capacity(ModeType.EXPRESS_RAIL, null, 80_000, 5.0, 20.0, null)),
                List.of());

        // 광역급행도 도로를 따라간다(사용자 결정) — 철도라 새 도로 다리값 칸은 없다
        ScenarioResult express = outcome.results().get(2);
        assertThat(express.getRouteLine()).startsWith("R ");
        assertThat(express.getRoadCost()).isNull();
        assertThat(express.getTravelTimeMin().doubleValue()).isCloseTo(13.5 / 20 * 60, org.assertj.core.data.Offset.offset(1.0));

        ScenarioResult tram = outcome.results().get(0), metro = outcome.results().get(1);
        // 소요시간 = 길이 ÷ 20km/h — 트램은 도로 13.5km, 지하철은 직선 10km
        assertThat(tram.getTravelTimeMin().doubleValue()).isCloseTo(13.5 / 20 * 60, org.assertj.core.data.Offset.offset(1.0));
        assertThat(metro.getTravelTimeMin().doubleValue()).isCloseTo(10.0 / 20 * 60, org.assertj.core.data.Offset.offset(0.2));
        assertThat(tram.getRouteLine()).startsWith("R ");
        assertThat(tram.getRoadCost()).isZero();
        assertThat(metro.getRouteLine()).isNull();
        assertThat(metro.getRoadCost()).isNull();
        // 역도 도로 위에 놓인다 — 가운데 역은 동쪽 길 위
        assertThat(tram.getStations().get(tram.getStations().size() / 2).getLongitude().doubleValue())
                .isCloseTo(126.998, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    @DisplayName("기존 도로가 1.5배를 넘게 돌면 새 도로 — 강을 건너는 몫은 일반국도 교량 단가로 더하고, 땅 위 공사비는 모른다고 적는다")
    void newRoadPaysForTheBridge() {
        // 동쪽 5.3km 를 도는 도로 — 20.6km 로 1.5배를 넘는다. 노선 가운데 동서로 놓인 강(남북 0.5km 폭)
        WaterBodies water = new WaterBodies("없는파일.geojson");
        water.add("가운데강", WaterBodies.RIVER, List.<double[][]>of(new double[][]{
                {37.5240, 126.95}, {37.5240, 127.00}, {37.5195, 127.00}, {37.5195, 126.95}, {37.5240, 126.95}}));
        CostCalculator calc = calculator(bracketRoad(0.06), water);
        var outcome = calc.calculate(scenario(10.0), List.of(standard(ModeType.TRAM, StructureType.AT_GRADE)),
                Map.of(ModeType.TRAM, capacity(ModeType.TRAM, null, 60_000, 1.0, 20.0, null)), List.of());

        ScenarioResult tram = outcome.results().get(0);
        assertThat(tram.getRouteLine()).startsWith("N ");
        // 강 폭 0.5km × 451억/km(일반국도 PSC Box 386.01억/km × 1.1686)
        assertThat(tram.getRoadCost()).isBetween(215L, 236L);
        assertThat(tram.getWarning()).contains("땅 위 공사비 모름");
    }

    /** 그린 노선(남북 10km) 가운데 강·만(위도 37.5195~37.5240) 양쪽 둑까지만 닿는 도로 둘 — 서로는 이어져 있지 않아 도로로는 갈 수 없다 */
    private static RoadNetwork banksOnly() {
        double[] a = ROUTE_POINTS.get(0), b = ROUTE_POINTS.get(1);
        return RoadNetwork.ofEdges(List.of(
                new RoadNetwork.Edge(0, 1, 0, new double[][]{a, {37.5240, a[1]}}),
                new RoadNetwork.Edge(2, 3, 0, new double[][]{{37.5195, a[1]}, b})));
    }

    @Test
    @DisplayName("강 양쪽 둑까지 도로가 닿으면 육지는 도로를 따라가고 강 위만 새 다리다 — 다리값은 강 폭만큼, 땅 위 공사비 「모름」 은 없다(11-19)")
    void newBridgeOnlyOverTheRiverWhenRoadsReachBothBanks() {
        WaterBodies water = new WaterBodies("없는파일.geojson");
        water.add("가운데강", WaterBodies.RIVER, List.<double[][]>of(new double[][]{
                {37.5240, 126.95}, {37.5240, 127.00}, {37.5195, 127.00}, {37.5195, 126.95}, {37.5240, 126.95}}));
        CostCalculator calc = calculator(banksOnly(), water);
        var outcome = calc.calculate(scenario(10.0), List.of(standard(ModeType.TRAM, StructureType.AT_GRADE)),
                Map.of(ModeType.TRAM, capacity(ModeType.TRAM, null, 60_000, 1.0, 20.0, null)), List.of());

        ScenarioResult tram = outcome.results().get(0);
        assertThat(tram.getRouteLine()).as("도로 · 새 다리 · 도로").startsWith("R ").contains("|N ").contains("|R ");
        assertThat(tram.getRoadCost()).as("강 폭 0.5km × 451억/km").isBetween(215L, 236L);
        assertThat(String.valueOf(tram.getWarning())).as("새 도로는 강 위뿐이라 땅 위 공사비 「모름」 이 없다").doesNotContain("땅 위 공사비 모름");
    }

    @Test
    @DisplayName("바다(만)도 같다 — 해안선을 건너는 몫만 새 다리, 양쪽 육지는 도로(11-19)")
    void newBridgeOnlyOverTheSeaWhenRoadsReachBothShores() {
        CostCalculator calc = calculator(banksOnly(), new WaterBodies("없는파일.geojson"), landWithBay());
        var outcome = calc.calculate(scenario(10.0), List.of(standard(ModeType.TRAM, StructureType.AT_GRADE)),
                Map.of(ModeType.TRAM, capacity(ModeType.TRAM, null, 60_000, 1.0, 20.0, null)), List.of());

        ScenarioResult tram = outcome.results().get(0);
        assertThat(tram.getRouteLine()).startsWith("R ").contains("|N ");
        assertThat(tram.getRoadCost()).isBetween(215L, 236L);
    }

    /** 큰 육지(위도 36~39, 경도 125~129) 안에 바다 만 하나 — 만은 위 강 테스트와 같은 자리(동서로 놓인 남북 0.5km 폭)다 */
    private static Coastline landWithBay() {
        double[][] continent = {{36.0, 125.0}, {36.0, 129.0}, {39.0, 129.0}, {39.0, 125.0}, {36.0, 125.0}};
        double[][] bay = {{37.5240, 126.95}, {37.5240, 127.00}, {37.5195, 127.00}, {37.5195, 126.95}, {37.5240, 126.95}};
        return Coastline.of(java.util.Arrays.asList(continent, bay));
    }

    @Test
    @DisplayName("새 도로가 바다를 건너면 다리값을 매긴다 — 바다 폭 0.5km × 451억/km, 하한이다(11-18)")
    void newRoadOverTheSeaPaysForTheBridge() {
        CostCalculator calc = calculator(bracketRoad(0.06), new WaterBodies("없는파일.geojson"), landWithBay());
        var outcome = calc.calculate(scenario(10.0), List.of(standard(ModeType.TRAM, StructureType.AT_GRADE)),
                Map.of(ModeType.TRAM, capacity(ModeType.TRAM, null, 60_000, 1.0, 20.0, null)), List.of());

        ScenarioResult tram = outcome.results().get(0);
        assertThat(tram.getRouteLine()).startsWith("N ");
        assertThat(tram.getRoadCost()).isBetween(215L, 236L);
        // 바다 위 몫은 땅 위 공사비 「모름」 길이에서 빠진다 — 안 빼면 바다를 땅으로 세어 경고가 틀린다
        assertThat(tram.getWarning()).contains("땅 위 공사비 모름");
    }

    @Test
    @DisplayName("강과 바다가 같은 자리에서 겹쳐도 다리값은 한 번만 — 하구에서 이중으로 세면 두 배가 된다")
    void riverAndSeaOverTheSameStretchAreCountedOnce() {
        WaterBodies water = new WaterBodies("없는파일.geojson");
        water.add("가운데강", WaterBodies.RIVER, List.<double[][]>of(new double[][]{
                {37.5240, 126.95}, {37.5240, 127.00}, {37.5195, 127.00}, {37.5195, 126.95}, {37.5240, 126.95}}));
        CostCalculator calc = calculator(bracketRoad(0.06), water, landWithBay());
        var outcome = calc.calculate(scenario(10.0), List.of(standard(ModeType.TRAM, StructureType.AT_GRADE)),
                Map.of(ModeType.TRAM, capacity(ModeType.TRAM, null, 60_000, 1.0, 20.0, null)), List.of());

        assertThat(outcome.results().get(0).getRoadCost()).as("215~236 — 두 배(450 안팎)면 이중 계산이다").isBetween(215L, 236L);
    }

    @Test
    @DisplayName("해안선 자료가 없으면 바다 위 다리값은 0 — 이전과 같다(꺼진 기능이 값을 바꾸지 않는다)")
    void withoutCoastlineThereIsNoSeaBridgeCost() {
        CostCalculator calc = calculator(bracketRoad(0.06), new WaterBodies("없는파일.geojson"), null);
        var outcome = calc.calculate(scenario(10.0), List.of(standard(ModeType.TRAM, StructureType.AT_GRADE)),
                Map.of(ModeType.TRAM, capacity(ModeType.TRAM, null, 60_000, 1.0, 20.0, null)), List.of());

        assertThat(outcome.results().get(0).getRoadCost()).isZero();
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
     * **노선이 그 수단의 역간격보다 짧으면 추천하지 않는다**(11-22 전국 훑기, 자율 모델 결정). 광역급행(표정속도 91.8km/h 는 역간격 6.92km 이상을 달릴 때의
     * 값)이 신촌~홍대 1.2km·한강 횡단 2.8km 에서도 1위였다 — 가속하다 끝나는 노선에서 그 속도는 나올 수 없다. 권장 연장 초과의 짝이고 같은 방식이다
     * (표에는 남기고 기본으로만 내세우지 않는다, 전부 걸리면 거르지 않는다).
     */
    @Test
    @DisplayName("역간격보다 짧은 노선에서는 그 수단을 추천하지 않는다 — 길이가 같으면 통과, 전부 걸리면 거르지 않는다")
    void doesNotRecommendModesLongerSpacedThanTheRoute() {
        var results = List.of(result(ModeType.EXPRESS_RAIL, 0.20), result(ModeType.DOUBLE_ELEC, 0.15),
                result(ModeType.HEAVY_METRO, 0.03));
        var capacities = Map.of(
                ModeType.EXPRESS_RAIL, capacity(ModeType.EXPRESS_RAIL, null, 60_000, 6.92, 91.8, null),
                ModeType.DOUBLE_ELEC, capacity(ModeType.DOUBLE_ELEC, null, 60_000, 2.62, 47.6, null),
                ModeType.HEAVY_METRO, capacity(ModeType.HEAVY_METRO, null, 80_000, 1.06, 32.4, 60.2));

        // 1.2km — 역간격이 이보다 긴 급행·복선전철은 못 내세운다
        assertThat(CostCalculator.recommended(results, capacities, 1.2)).isEqualTo(ModeType.HEAVY_METRO);
        // 4km — 급행(6.92)만 걸린다
        assertThat(CostCalculator.recommended(results, capacities, 4.0)).isEqualTo(ModeType.DOUBLE_ELEC);
        // 역간격과 정확히 같은 길이는 통과한다(경계)
        assertThat(CostCalculator.recommended(results, capacities, 6.92)).isEqualTo(ModeType.EXPRESS_RAIL);
        // 12km — 모두 통과, B/C 최고안
        assertThat(CostCalculator.recommended(results, capacities, 12.0)).isEqualTo(ModeType.EXPRESS_RAIL);
        // 전부 걸리면(0.5km — 가장 짧은 역간격 1.06 도 못 채운다) 길이를 따지지 않는다
        assertThat(CostCalculator.recommended(results, capacities, 0.5)).isEqualTo(ModeType.EXPRESS_RAIL);
    }

    @Test
    @DisplayName("역간격을 모르는 수단(NULL)에는 하한이 없다 — 모르는 것을 근거로 거르지 않는다")
    void unknownSpacingHasNoLowerBound() {
        var results = List.of(result(ModeType.SINGLE_ELEC, 0.9), result(ModeType.HEAVY_METRO, 0.5));
        var capacities = Map.of(
                ModeType.SINGLE_ELEC, capacity(ModeType.SINGLE_ELEC, null, 25_000, null, 45.0, null),
                ModeType.HEAVY_METRO, capacity(ModeType.HEAVY_METRO, null, 80_000, 1.06, 32.4, 60.2));

        assertThat(CostCalculator.recommended(results, capacities, 0.8)).isEqualTo(ModeType.SINGLE_ELEC);
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

    /** 노선 한가운데에만 사람이 사는 지역. 양 끝 1km 안에는 아무도 없다 */
    private CostCalculator midpointPeople() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("population");
        java.nio.file.Path census = dir.resolve("census.csv"), workers = dir.resolve("workers.csv");
        java.nio.file.Files.writeString(census, "adm_cd,latitude,longitude,population,dong_name\n1,37.5215,126.9780,100000,가운데동\n");
        java.nio.file.Files.writeString(workers, "dong_code,latitude,longitude,workers\n1,37.5215,126.9780,50000\n");
        PopulationIndex index = new PopulationIndex(census.toString(), workers.toString());
        index.load();

        Map<String, Double> values = new java.util.HashMap<>(PARAMETERS);
        values.put("demand_intercept", -2.5);
        values.put("demand_pop_elasticity", 0.47);
        values.put("demand_worker_elasticity", 0.69);
        values.put("demand_light_rail_coef", -0.7);
        BenefitParameterRepository parameters = Mockito.mock(BenefitParameterRepository.class);
        Mockito.when(parameters.findAll()).thenReturn(values.entrySet().stream()
                .map(e -> newInstance(BenefitParameter.class, "paramName", e.getKey(), "value", BigDecimal.valueOf(e.getValue())))
                .toList());
        ExistingLineFinder existingLines = new ExistingLineFinder("없는파일.csv", "없는지방파일.csv");
        existingLines.load();
        IntercityRailFinder intercity = new IntercityRailFinder("없는KTX파일.csv");
        intercity.load();
        return new CostCalculator(index, new BenefitCalculator(parameters), new StationPlanner(index),
                new ExistingOptions(existingLines, intercity, new KtxTravelTime("없는파일.csv"), new UrbanLineSpeed("없는파일.csv")));
    }

    private Integer ridersOn(CostCalculator calculator, List<double[]> points) {
        Scenario s = Scenario.builder().title("테스트").totalLengthKm(BigDecimal.valueOf(10.0))
                .preferredStructure(StructureType.ELEVATED).build();
        s.replacePoints(points.stream()
                .map(p -> newInstance(com.railfeas.scenario.RoutePoint.class,
                        "latitude", BigDecimal.valueOf(p[0]), "longitude", BigDecimal.valueOf(p[1])))
                .toList(), BigDecimal.valueOf(10.0));
        // 중전철 — 수단 배수가 없는 기준값을 본다(경전철은 lightRailGetsItsOwnDemand 가 본다)
        ModeCapacity capacity = capacity(ModeType.HEAVY_METRO, null, 80_000, 2.0, 30.0, null);
        return calculator.calculate(s, List.of(standard(ModeType.HEAVY_METRO, StructureType.ELEVATED)),
                Map.of(ModeType.HEAVY_METRO, capacity), List.of()).results().get(0).getEstimatedRidership();
    }

    @Test
    @DisplayName("선 가운데의 인구도 센다 — 찍은 점 둘레만 세면 양 끝에 사람이 없는 노선의 수요가 없다")
    void countsPeopleAlongTheLine() throws Exception {
        Integer riders = ridersOn(midpointPeople(), ROUTE_POINTS);

        // exp(-2.5 + 0.47·ln(100000) + 0.69·ln(50001))
        assertThat(riders).isEqualTo((int) Math.round(Math.exp(-2.5 + 0.47 * Math.log(100_000) + 0.69 * Math.log(50_001))));
    }

    @Test
    @DisplayName("같은 선에 경유점을 더 찍어도 수요가 같다")
    void waypointsDoNotChangeDemand() throws Exception {
        CostCalculator calculator = midpointPeople();
        List<double[]> withWaypoints = List.of(ROUTE_POINTS.get(0), new double[]{37.5215, 126.9780},
                ROUTE_POINTS.get(1));

        assertThat(ridersOn(calculator, withWaypoints)).isEqualTo(ridersOn(calculator, ROUTE_POINTS));
    }

    @Test
    @DisplayName("경전철 행만 이용객에 경전철 배수를 곱한다 — 다른 수단은 그대로")
    void lightRailGetsItsOwnDemand() throws Exception {
        CostCalculator calculator = midpointPeople();
        Scenario s = Scenario.builder().title("테스트").totalLengthKm(BigDecimal.valueOf(10.0))
                .preferredStructure(StructureType.ELEVATED).build();
        s.replacePoints(ROUTE_POINTS.stream()
                .map(p -> newInstance(com.railfeas.scenario.RoutePoint.class,
                        "latitude", BigDecimal.valueOf(p[0]), "longitude", BigDecimal.valueOf(p[1])))
                .toList(), BigDecimal.valueOf(10.0));
        Map<ModeType, ModeCapacity> caps = Map.of(
                ModeType.LIGHT_RAIL, capacity(ModeType.LIGHT_RAIL, null, 60_000, 2.0, 30.0, null),
                ModeType.HEAVY_METRO, capacity(ModeType.HEAVY_METRO, null, 80_000, 2.0, 30.0, null));
        var results = calculator.calculate(s, List.of(standard(ModeType.LIGHT_RAIL, StructureType.ELEVATED),
                standard(ModeType.HEAVY_METRO, StructureType.ELEVATED)), caps, List.of()).results();
        int base = (int) Math.round(Math.exp(-2.5 + 0.47 * Math.log(100_000) + 0.69 * Math.log(50_001)));
        Integer light = results.stream().filter(r -> r.getModeType() == ModeType.LIGHT_RAIL).findFirst().orElseThrow().getEstimatedRidership();
        Integer heavy = results.stream().filter(r -> r.getModeType() == ModeType.HEAVY_METRO).findFirst().orElseThrow().getEstimatedRidership();

        assertThat(heavy).isEqualTo(base);
        assertThat(light).isEqualTo((int) Math.round(base * Math.exp(-0.7)));
    }

    // ── 철도 행은 지도에 그린 선(역을 잇는 선)으로 잰다 ──────────────────────────────────
    // 정차역이 큰길로 옮겨지면(11-12) 노선 길이도 지도 선도 그 역들을 지난다. 터널·구역만 찍은 직선으로 재 지도의 갈색·파란 토막이
    // 선 밖 허공에 떴고, 표의 터널비도 지도와 다른 선의 값이었다(11-26 사용자 요청)

    /** 그린 선(남북 10km)에서 동쪽 약 0.35km 를 나란히 가는 큰길 — 정차역이 그 위로 옮겨진다 */
    private static RoadNetwork parallelRoad() {
        double east = 0.004;
        double[] a = ROUTE_POINTS.get(0), b = ROUTE_POINTS.get(1);
        return RoadNetwork.ofEdges(List.of(new RoadNetwork.Edge(0, 1, 0, new double[][]{{a[0], a[1] + east}, {b[0], b[1] + east}})));
    }

    private ScenarioResult railOnParallelRoad(TunnelKm tunnel, List<com.railfeas.reference.RestrictedZone> zones) {
        IntercityRailFinder intercity = new IntercityRailFinder("없는KTX파일.csv");
        intercity.load();
        var capacity = capacity(ModeType.HEAVY_METRO, null, 80_000, 2.0, 30.0, null);
        return calculator(intercity, tunnel, RoadNetwork.of(List.of()), new WaterBodies("없는파일.geojson"), null, parallelRoad())
                .calculate(scenario(10.0), List.of(standard(ModeType.HEAVY_METRO, StructureType.ELEVATED)),
                        Map.of(ModeType.HEAVY_METRO, capacity), zones)
                .results().get(0);
    }

    @Test
    @DisplayName("철도 행의 터널은 역을 잇는 선으로 잰다 — 역이 큰길로 옮겨지면 지도에 그린 선과 같은 선이다(11-26)")
    void railTunnelIsMeasuredOnTheLineThroughStations() {
        List<List<double[]>> asked = new java.util.ArrayList<>();
        TunnelKm recording = (route, standards) -> {
            asked.add(route);
            return new TunnelKm.Tunnels(java.util.OptionalDouble.of(0), java.util.OptionalDouble.of(0));
        };
        ScenarioResult r = railOnParallelRoad(recording, List.of());

        List<double[]> stations = r.getStations().stream()
                .map(s -> new double[]{s.getLatitude().doubleValue(), s.getLongitude().doubleValue()}).toList();
        assertThat(stations.get(stations.size() / 2)[1]).as("가운데 역이 동쪽 큰길로 옮겨졌다").isGreaterThan(ROUTE_POINTS.get(0)[1] + 0.003);
        assertThat(asked).as("철도 수단마다 한 번").hasSize(1);
        assertThat(asked.get(0)).as("찍은 직선이 아니라 역을 잇는 선").hasSameSizeAs(stations);
        for (int i = 0; i < stations.size(); i++) {
            assertThat(asked.get(0).get(i)[0]).isCloseTo(stations.get(i)[0], org.assertj.core.data.Offset.offset(1e-6));
            assertThat(asked.get(0).get(i)[1]).isCloseTo(stations.get(i)[1], org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    @Test
    @DisplayName("철도 행의 구역 경고도 역을 잇는 선으로 본다 — 직선에서 0.35km 떨어졌어도 역이 지나는 구역이면 알린다(11-26)")
    void railZoneWarningFollowsTheLineThroughStations() {
        // 큰길 위(직선에서 동쪽 0.35km) 반경 0.2km — 직선은 비껴가고 역을 잇는 선은 지난다
        var zone = newInstance(com.railfeas.reference.RestrictedZone.class,
                "name", "큰길 옆 보호구역",
                "centerLat", BigDecimal.valueOf(37.5215),
                "centerLng", BigDecimal.valueOf(126.9820),
                "radiusKm", BigDecimal.valueOf(0.2));

        assertThat(railOnParallelRoad(TunnelKm.NONE, List.of(zone)).getWarning()).contains("큰길 옆 보호구역");
    }

    // ── 산 구간 터널비 ─────────────────────────────────────────────────────────
    // 비교표는 그린 그대로의 노선을 센다 — 산을 지나면 뚫어야 하므로 뚫는 비용이 건설비에 든다(11-09).
    // 돌아가는 안은 사용자가 선형을 바꿔야 그 노선이 되므로 표에 넣지 않는다

    private List<ScenarioResult> withTunnel(TunnelKm tunnel, List<CostStandard> standards) {
        Scenario s = Scenario.builder().title("테스트").totalLengthKm(BigDecimal.valueOf(10.0))
                .preferredStructure(StructureType.ELEVATED).build();
        s.replacePoints(ROUTE_POINTS.stream()
                .map(p -> newInstance(com.railfeas.scenario.RoutePoint.class,
                        "latitude", BigDecimal.valueOf(p[0]), "longitude", BigDecimal.valueOf(p[1])))
                .toList(), BigDecimal.valueOf(10.0));
        Map<ModeType, ModeCapacity> caps = new java.util.HashMap<>();
        for (ModeType m : ModeType.values()) {
            caps.put(m, capacity(m, null, 80_000, 2.0, 30.0, null));
        }
        IntercityRailFinder intercity = new IntercityRailFinder("없는KTX파일.csv");
        intercity.load();
        return calculator(intercity, tunnel).calculate(s, standards, caps, List.of()).results();
    }

    private static ScenarioResult row(List<ScenarioResult> rows, ModeType mode, StructureType structure) {
        return rows.stream().filter(r -> r.getModeType() == mode && r.getStructureType() == structure)
                .findFirst().orElseThrow();
    }

    private final List<CostStandard> tunnelStandards = List.of(
            standard(ModeType.HEAVY_METRO, StructureType.ELEVATED, 500L),
            standard(ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 800L),
            standard(ModeType.TRAM, StructureType.AT_GRADE, 300L),
            standard(ModeType.BRT_HIGH, StructureType.AT_GRADE, 100L));

    private static TunnelKm tunnels(double rail, double road) {
        return (route, standards) -> new TunnelKm.Tunnels(java.util.OptionalDouble.of(rail), java.util.OptionalDouble.of(road));
    }

    @Test
    @DisplayName("산 구간이 있으면 지상·고가 행에 터널 km × KDI km당 추가분을 더한다 — 지하 행은 0")
    void tunnelCostIsAddedToSurfaceRows() {
        List<ScenarioResult> none = withTunnel(tunnels(0, 0), tunnelStandards);
        List<ScenarioResult> two = withTunnel(tunnels(2.0, 1.0), tunnelStandards);

        long heavy = Math.round(2.0 * (319 - 317) * MountainCost.DEFLATOR_2019);
        ScenarioResult elevated = row(two, ModeType.HEAVY_METRO, StructureType.ELEVATED);
        assertThat(elevated.getTunnelCost()).isEqualTo(heavy);
        assertThat(elevated.getTotalCost())
                .isEqualTo(row(none, ModeType.HEAVY_METRO, StructureType.ELEVATED).getTotalCost() + heavy);
        assertThat(row(two, ModeType.HEAVY_METRO, StructureType.UNDERGROUND).getTunnelCost()).isZero();
        assertThat(row(none, ModeType.HEAVY_METRO, StructureType.ELEVATED).getTunnelCost()).isZero();
    }

    @Test
    @DisplayName("트램은 경량전철 NATM − 노면전차, BRT 는 도로 기울기로 잰 터널 km × (도로 터널 − 그 행 km단가) — 경고 없이 더한다")
    void tramAndBrtUseKdiRoadAndLightRail() {
        List<ScenarioResult> two = withTunnel(tunnels(2.0, 1.0), tunnelStandards);
        double f = MountainCost.DEFLATOR_2019;

        ScenarioResult tram = row(two, ModeType.TRAM, StructureType.AT_GRADE);
        assertThat(tram.getTunnelCost()).isEqualTo(Math.round(2.0 * (265.6 - 13.4) * f));
        ScenarioResult brt = row(two, ModeType.BRT_HIGH, StructureType.AT_GRADE);
        assertThat(brt.getTunnelCost()).isEqualTo(Math.round(1.0 * (145.84 * f - 100)));   // 도로 터널 1.0km
        for (ScenarioResult r : List.of(tram, brt)) {
            assertThat(r.getWarning() == null ? "" : r.getWarning()).doesNotContain("산 구간");
        }
    }

    @Test
    @DisplayName("지형 자료가 없으면 터널비를 모른다 — 더하지 않고, 행마다 경고를 달지도 않는다")
    void noTerrainMeansUnknownWithoutNoise() {
        List<ScenarioResult> rows = withTunnel(TunnelKm.NONE, tunnelStandards);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getTunnelCost()).isNull();
            assertThat(r.getWarning() == null ? "" : r.getWarning()).doesNotContain("산 구간");
        });
    }

    @Test
    @DisplayName("경고는 warning 칸(VARCHAR 200)을 넘지 않는다")
    void warningFitsColumn() {
        String longWarning = "가".repeat(150) + " / " + "나".repeat(150);
        assertThat(CostCalculator.fitWarning(longWarning)).hasSizeLessThanOrEqualTo(200).endsWith("…");
        assertThat(CostCalculator.fitWarning("짧다")).isEqualTo("짧다");
        assertThat(CostCalculator.fitWarning(null)).isNull();
    }

    @Test
    @DisplayName("뺀 역의 경고는 까닭별로 — 물·다리·터널 위라 뺀 역을 「인구가 없어」 로 적지 않는다(11-21)")
    void droppedStationNotesSayTheRealReason() {
        assertThat(CostCalculator.droppedNotes(3, 1)).containsExactly(
                "2곳은 반경 500m 안에 인구가 없어 역을 놓지 않았습니다", "1곳은 물·다리·터널 위라 역을 놓지 않았습니다");
        assertThat(CostCalculator.droppedNotes(1, 1)).containsExactly("1곳은 물·다리·터널 위라 역을 놓지 않았습니다");
        assertThat(CostCalculator.droppedNotes(2, 0)).containsExactly("2곳은 반경 500m 안에 인구가 없어 역을 놓지 않았습니다");
        assertThat(CostCalculator.droppedNotes(0, 0)).isEmpty();
        // 최소 역 수로 받쳐 뺀 수가 까닭 수보다 작으면 — 음수·0 문장을 만들지 않는다
        assertThat(CostCalculator.droppedNotes(1, 2)).containsExactly("1곳은 물·다리·터널 위라 역을 놓지 않았습니다");
    }
}
