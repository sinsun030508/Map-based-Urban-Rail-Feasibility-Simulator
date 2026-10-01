package com.railfeas.calc;

import com.railfeas.common.ModeType;
import com.railfeas.geo.Haversine;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.ModeCapacity;
import com.railfeas.reference.RestrictedZone;
import com.railfeas.scenario.RoutePoint;
import com.railfeas.scenario.Scenario;
import com.railfeas.scenario.ScenarioResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
    private static final double[] CBD = {37.5665, 126.9780};   // 서울시청

    private final PopulationIndex population;
    private final BenefitCalculator benefits;

    public CostCalculator(PopulationIndex population, BenefitCalculator benefits) {
        this.population = population;
        this.benefits = benefits;
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
                .mapToDouble(p -> Haversine.distanceKm(p[0], p[1], CBD[0], CBD[1]))
                .min().orElse(0);
        Integer dailyRiders = benefits.estimateDailyRiders(params, people, workers, cbdDistance);
        Integer peak = benefits.peakPphpd(params, dailyRiders);

        List<ScenarioResult> results = new ArrayList<>();
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

            results.add(ScenarioResult.builder()
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
                    .build());
        }
        return new Outcome(results, people, recommended(results));
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
                notes.add("수송능력 대비 과잉 투자");
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
                          ModeType recommendedMode) {
    }
}
