package com.railfeas.geo;

import java.util.List;

/** 위경도 좌표열의 거리 계산. 실제 선형이 아니라 직선 거리 합이라 실제보다 짧게 나온다. */
public final class Haversine {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private Haversine() {
    }

    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 점에서 선분 a–b 까지의 최단 거리(km). 좌표는 {위도, 경도}.
     *
     * **끝점까지가 아니라 선분까지다.** 끝점만 재면 양 끝이 멀고 가운데가 지나는 곳을 놓친다 —
     * 규제 구역 검사가 그랬다(`CostCalculator.zoneWarning`). 수선의 발이 선분 밖이면 가까운
     * 끝점까지를 잰다 — 무한 직선으로 재면 연장선 위의 먼 점까지 "지나간다" 가 된다.
     *
     * 점 둘레를 평면으로 펴서 잰다(`StationPlanner`·`WaterDetour` 와 같은 근사). 경도 1도의 km 를
     * 점의 위도로 정하므로 점 근처는 정확하고 멀어질수록 어긋나는데, 쓰임이 "반경 몇 km 안인가" 라
     * 판정에 걸리는 것은 점 가까이를 지나는 선분뿐이다.
     */
    public static double distanceToSegmentKm(double lat, double lng, double[] a, double[] b) {
        double kmPerLat = 110.574;
        double kmPerLng = 111.320 * Math.cos(Math.toRadians(lat));
        // 점을 원점으로 둔 km 평면
        double ax = (a[1] - lng) * kmPerLng;
        double ay = (a[0] - lat) * kmPerLat;
        double dx = (b[1] - a[1]) * kmPerLng;
        double dy = (b[0] - a[0]) * kmPerLat;
        double len2 = dx * dx + dy * dy;
        // 원점에서 내린 수선의 발이 선분 위 어디인가 (0 = a, 1 = b). 길이 0 이면 a 한 점이다
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }

    /** 좌표를 순서대로 이은 총 연장(km) */
    public static double totalLengthKm(List<double[]> points) {
        double sum = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            sum += distanceKm(a[0], a[1], b[0], b[1]);
        }
        return sum;
    }
}
