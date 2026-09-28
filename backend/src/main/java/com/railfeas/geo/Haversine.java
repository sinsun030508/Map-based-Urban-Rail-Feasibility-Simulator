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
