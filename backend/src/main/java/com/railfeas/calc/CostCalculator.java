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
 * 수단×구조 조합별 건설비를 계산한다. 계수는 cost_standard (etl/cost_model.py 산출).
 *
 * 수요·편익·B/C 는 SGIS 연동 전이라 비워 둔다. 지금 나오는 것은 비용뿐이다.
 * 정차역 수도 실제 배치가 아니라 수단별 표준 역간격으로 나눈 추정값이다.
 */
@Component
public class CostCalculator {

    private static final int MIN_STATIONS = 2;   // 출발·도착

    public List<ScenarioResult> calculate(Scenario scenario,
                                          List<CostStandard> standards,
                                          Map<ModeType, ModeCapacity> capacities,
                                          List<RestrictedZone> zones) {
        double lengthKm = scenario.getTotalLengthKm().doubleValue();
        String zoneWarning = zoneWarning(scenario.getPoints(), zones);

        List<ScenarioResult> results = new ArrayList<>();
        for (CostStandard std : standards) {
            ModeCapacity capacity = capacities.get(std.getModeType());
            int stations = estimateStations(lengthKm, capacity);
            long cost = std.getFixedCost()
                    + Math.round(std.getCostPerKm() * lengthKm)
                    + (long) std.getCostPerStation() * stations;

            results.add(ScenarioResult.builder()
                    .scenario(scenario)
                    .modeType(std.getModeType())
                    .structureType(std.getStructureType())
                    .standardId(std.getId())
                    .stationCount(stations)
                    .totalCost(cost)
                    .travelTimeMin(travelTimeMin(lengthKm, capacity))
                    .feasible(true)          // 수송능력 판정은 수요 추정 후에 가능하다
                    .warning(warning(lengthKm, capacity, zoneWarning))
                    .build());
        }
        return results;
    }

    private int estimateStations(double lengthKm, ModeCapacity capacity) {
        if (capacity == null || capacity.getSpacingKm() == null) {
            return MIN_STATIONS;
        }
        int stations = (int) Math.round(lengthKm / capacity.getSpacingKm().doubleValue());
        return Math.max(MIN_STATIONS, stations);
    }

    private BigDecimal travelTimeMin(double lengthKm, ModeCapacity capacity) {
        if (capacity == null || capacity.getSpeedKmh() == null) {
            return null;
        }
        double minutes = lengthKm / capacity.getSpeedKmh().doubleValue() * 60;
        return BigDecimal.valueOf(minutes).setScale(1, RoundingMode.HALF_UP);
    }

    /** 경고는 계산을 막지 않는다. 판단 근거만 덧붙인다. */
    private String warning(double lengthKm, ModeCapacity capacity, String zoneWarning) {
        List<String> notes = new ArrayList<>();
        if (capacity != null && capacity.getMaxLengthKm() != null
                && lengthKm > capacity.getMaxLengthKm().doubleValue()) {
            notes.add("권장 연장 초과 — 복선전철 대안 참고");
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
}
