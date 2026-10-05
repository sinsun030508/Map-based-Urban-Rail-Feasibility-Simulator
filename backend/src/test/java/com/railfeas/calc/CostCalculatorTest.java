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
    private static final Map<String, Double> PARAMETERS = Map.of(
            "baseline_speed_kmh", 21.3,
            "time_value_won_per_hour", 7231.0,
            "analysis_years", 40.0,
            "construction_years", 5.0,
            "discount_rate", 0.045,
            "discount_rate_late", 0.035,
            "discount_switch_year", 30.0,
            "peak_hour_ratio", 0.0987,
            "peak_direction_ratio", 0.3165);

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
        ExistingLineFinder existingLines = new ExistingLineFinder("없는파일.csv");
        existingLines.load();

        return new CostCalculator(index, new BenefitCalculator(parameters),
                new StationPlanner(index), existingLines);
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
}
