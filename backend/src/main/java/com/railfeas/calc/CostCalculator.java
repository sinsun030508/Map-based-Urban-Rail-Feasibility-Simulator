package com.railfeas.calc;

import com.railfeas.common.ModeType;
import com.railfeas.geo.Haversine;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.ModeCapacity;
import com.railfeas.reference.RestrictedZone;
import com.railfeas.scenario.RoutePoint;
import com.railfeas.scenario.Scenario;
import com.railfeas.scenario.ScenarioResult;
import com.railfeas.scenario.Station;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 수단×구조 조합별로 건설비·수요·편익·B/C 를 계산한다.
 *
 * 계수 출처
 *   비용  cost_standard (etl/cost_model.py, 교차검증 1.25배)
 *   수요  benefit_parameter demand_* (etl/demand_model.py, 교차검증 1.75배)
 *   편익  benefit_parameter (S7) — 시간가치·할인율은 투자평가지침 제7판,
 *         첨두율·기준속도는 지침에 원단위가 없어 가정값이다
 *
 * 정차역 수는 실제 배치가 아니라 수단별 표준 역간격으로 나눈 추정값이다.
 */
@Component
public class CostCalculator {

    private static final int MIN_STATIONS = 2;        // 출발·도착
    private static final double BUFFER_KM = 1.0;      // 인구 집계 반경
    /**
     * 도심 접근성 대리변수의 기준점. **그 지역의 도심이어야 한다.**
     *
     * 서울시청 하나로 고정했더니 부산 노선이 326km 로 잡혀, 수요 회귀 표본의 범위
     * (0.1~88.7km)를 3.7배 외삽했다. 도심거리 항만으로 예측이 0.02배로 깎여
     * B/C 가 0.019 처럼 **숫자는 나오지만 뜻이 없는 값**이 된다.
     * 부산 사람이 서울로 출근하지 않으니 가장 가까운 광역시 도심까지를 잰다.
     *
     * `etl/sgis_population.py` 의 `CBDS` 와 **같은 목록이어야 한다** — 어긋나면
     * 학습과 예측이 다른 기준을 쓰게 된다.
     */
    private static final double[][] CBDS = {
            {37.5665, 126.9780},   // 서울시청
            {35.1796, 129.0756},   // 부산시청
            {35.8714, 128.6014},   // 대구시청
            {35.1600, 126.8514},   // 광주시청
            {36.3504, 127.3845},   // 대전시청
    };

    private final PopulationIndex population;
    private final BenefitCalculator benefits;
    private final StationPlanner planner;
    private final ExistingLineFinder existingLines;

    /** 가장 가까운 도심까지의 거리. 수도권은 서울시청이 가장 가까워 값이 바뀌지 않는다 */
    private static double nearestCbdKm(double[] point) {
        double best = Double.MAX_VALUE;
        for (double[] cbd : CBDS) {
            best = Math.min(best, Haversine.distanceKm(point[0], point[1], cbd[0], cbd[1]));
        }
        return best;
    }

    public CostCalculator(PopulationIndex population, BenefitCalculator benefits,
                          StationPlanner planner, ExistingLineFinder existingLines) {
        this.population = population;
        this.benefits = benefits;
        this.planner = planner;
        this.existingLines = existingLines;
    }

    public Outcome calculate(Scenario scenario,
                             List<CostStandard> standards,
                             Map<ModeType, ModeCapacity> capacities,
                             List<RestrictedZone> zones) {
        double lengthKm = scenario.getTotalLengthKm().doubleValue();
        List<double[]> points = scenario.getPoints().stream()
                .map(p -> new double[]{p.getLatitude().doubleValue(),
                        p.getLongitude().doubleValue()})
                .toList();

        String zoneWarning = zoneWarning(scenario.getPoints(), zones);
        BenefitCalculator.Params params = benefits.load();

        long people = population.populationNear(points, BUFFER_KM);
        long workers = population.workersNear(points, BUFFER_KM);
        double cbdDistance = points.stream()
                .mapToDouble(CostCalculator::nearestCbdKm)
                .min().orElse(0);
        Integer dailyRiders = benefits.estimateDailyRiders(params, people, workers, cbdDistance);
        Integer peak = benefits.peakPphpd(params, dailyRiders, lengthKm);

        List<ScenarioResult> results = new ArrayList<>();
        // 역 배치는 역간격이 같으면 같다 — 수단당 한 번만 계산해 지하·고가가 함께 쓴다
        Map<ModeType, List<StationPlanner.Placed>> placedByMode = new HashMap<>();
        for (CostStandard std : standards) {
            ModeCapacity capacity = capacities.get(std.getModeType());
            int stations = estimateStations(lengthKm, capacity);
            long cost = std.getFixedCost()
                    + Math.round(std.getCostPerKm() * lengthKm)
                    + (long) std.getCostPerStation() * stations;

            Double speed = capacity == null || capacity.getSpeedKmh() == null
                    ? null : capacity.getSpeedKmh().doubleValue();
            Long benefit = speed == null ? null
                    : benefits.benefitTotal(params, dailyRiders, lengthKm, speed);

            ScenarioResult result = ScenarioResult.builder()
                    .scenario(scenario)
                    .modeType(std.getModeType())
                    .structureType(std.getStructureType())
                    .standardId(std.getId())
                    .stationCount(stations)
                    .totalCost(cost)
                    .estimatedRidership(dailyRiders)
                    .peakPphpd(peak)
                    .travelTimeMin(travelTimeMin(lengthKm, speed))
                    .benefitTotal(benefit)
                    // B/C 는 양쪽 다 현재가치로 본다. total_cost 에는 명목 총액을 남긴다
                    .bcRatio(benefits.bcRatio(benefit, benefits.costPresentValue(params, cost)))
                    .feasible(isFeasible(peak, capacity))
                    .warning(warning(lengthKm, peak, capacity, zoneWarning, people))
                    .build();
            List<StationPlanner.Placed> placed = placedByMode.computeIfAbsent(
                    std.getModeType(),
                    mode -> planner.plan(points, stations, spacingOf(capacity)));
            attachStations(result, placed, dailyRiders);
            results.add(result);
        }
        // 이미 전철이 다니는 길이면 신설안이 1위로 올라와도 쓸모가 없다 — 먼저 알린다
        ExistingLineFinder.Match existing = existingLines.find(points);
        return new Outcome(results, people, recommended(results),
                existing == null ? null : existing.message());
    }

    /**
     * 배치된 역을 결과에 붙이고, 예상 이용객을 역세권 인구 비율로 나눠 준다.
     * 노선 전체 이용객을 인구 가중으로 쪼개는 근사다 — 역별 회귀를 따로 돌리지 않는다.
     */
    private void attachStations(ScenarioResult result, List<StationPlanner.Placed> placed,
                                Integer dailyRiders) {
        long scoreSum = placed.stream().mapToLong(StationPlanner.Placed::score).sum();
        for (int i = 0; i < placed.size(); i++) {
            StationPlanner.Placed p = placed.get(i);
            Integer users = dailyRiders == null || scoreSum <= 0 ? null
                    : (int) Math.round((double) dailyRiders * p.score() / scoreSum);
            result.addStation(Station.builder()
                    .result(result)
                    .sequence(i)
                    .name(p.name())
                    .latitude(BigDecimal.valueOf(p.lat()).setScale(7, RoundingMode.HALF_UP))
                    .longitude(BigDecimal.valueOf(p.lng()).setScale(7, RoundingMode.HALF_UP))
                    .estimatedDailyUsers(users)
                    .recommended(true)
                    .build());
        }
    }

    private double spacingOf(ModeCapacity capacity) {
        return capacity == null || capacity.getSpacingKm() == null
                ? 1.0 : capacity.getSpacingKm().doubleValue();
    }

    /** 수송능력을 넘으면 그 수단으로는 못 나른다 — 유일한 탈락 조건 */
    private boolean isFeasible(Integer peak, ModeCapacity capacity) {
        return peak == null || capacity == null || capacity.getPphpdMax() == null
                || peak <= capacity.getPphpdMax();
    }

    /** 운행 가능한 안 중 B/C 최대. 수요를 못 구하면 추천하지 않는다 */
    private ModeType recommended(List<ScenarioResult> results) {
        return results.stream()
                .filter(r -> Boolean.TRUE.equals(r.getFeasible()) && r.getBcRatio() != null)
                .max((a, b) -> a.getBcRatio().compareTo(b.getBcRatio()))
                .map(ScenarioResult::getModeType)
                .orElse(null);
    }

    private int estimateStations(double lengthKm, ModeCapacity capacity) {
        if (capacity == null || capacity.getSpacingKm() == null) {
            return MIN_STATIONS;
        }
        return Math.max(MIN_STATIONS,
                (int) Math.round(lengthKm / capacity.getSpacingKm().doubleValue()));
    }

    private BigDecimal travelTimeMin(double lengthKm, Double speedKmh) {
        if (speedKmh == null || speedKmh <= 0) {
            return null;
        }
        return BigDecimal.valueOf(lengthKm / speedKmh * 60).setScale(1, RoundingMode.HALF_UP);
    }

    /** 경고는 계산을 막지 않는다. 판단 근거만 덧붙인다. */
    private String warning(double lengthKm, Integer peak, ModeCapacity capacity,
                           String zoneWarning, long people) {
        List<String> notes = new ArrayList<>();
        if (capacity != null && capacity.getMaxLengthKm() != null
                && lengthKm > capacity.getMaxLengthKm().doubleValue()) {
            notes.add("권장 연장 초과 — 복선전철 대안 참고");
        }
        if (peak != null && capacity != null) {
            if (capacity.getPphpdMax() != null && peak > capacity.getPphpdMax()) {
                notes.add("수송능력 초과");
            } else if (capacity.getPphpdMin() != null && peak < capacity.getPphpdMin()) {
                // 하한은 수송능력이 아니라 **실제 운영 노선의 최저 단면**이다 (S1 주석 참고)
                notes.add("과잉 투자 검토 — 실제 운영 노선 최저 수준("
                        + String.format("%,d", capacity.getPphpdMin()) + "명/시)보다 낮다");
            }
        }
        if (people <= 0) {
            notes.add("인구 자료 없음 — 수집 범위(수도권) 밖이라 수요·B/C 를 계산하지 못했다");
        }
        if (zoneWarning != null) {
            notes.add(zoneWarning);
        }
        return notes.isEmpty() ? null : String.join(" / ", notes);
    }

    /**
     * 규제 구역 근처를 지나는지 본다.
     * 구역 좌표·반경이 근사치라 "통과"가 아니라 "확인 필요"로만 안내한다.
     */
    private String zoneWarning(List<RoutePoint> points, List<RestrictedZone> zones) {
        for (RestrictedZone zone : zones) {
            double radius = zone.getRadiusKm().doubleValue();
            for (RoutePoint p : points) {
                double d = Haversine.distanceKm(
                        p.getLatitude().doubleValue(), p.getLongitude().doubleValue(),
                        zone.getCenterLat().doubleValue(), zone.getCenterLng().doubleValue());
                if (d <= radius) {
                    return zone.getName() + " 인근 — 확인 필요";
                }
            }
        }
        return null;
    }

    /** 계산 결과와 함께 시나리오에 기록할 값들 */
    public record Outcome(List<ScenarioResult> results, long population1km,
                          ModeType recommendedMode, String existingLine) {
    }
}
