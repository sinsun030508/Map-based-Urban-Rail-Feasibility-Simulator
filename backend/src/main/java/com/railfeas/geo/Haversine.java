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

    /** 반지름이 이 비율 안에서 경계에 가까우면 부동소수점 오차(~1e-15)를 넘어서는 여유를 두고 옛 계산으로 되돌린다 */
    private static final double MARGIN = 1e-9;

    /**
     * 두 점 사이 거리가 {@code radiusKm} 이하인가 — **{@code distanceKm(...) <= radiusKm} 과 항상 같은 답**이다(`HaversineWithinTest` 가 무작위 20만 쌍과 경계 ulp 단위로 증명).
     *
     * <p>왜 따로 있나(11-22 JFR 프로파일): 최악 노선 계산 6초의 CPU 샘플 57% 가 `PopulationIndex.near` 의 `distanceKm` — 특히 JDK 의 느린 `StrictMath.atan2`·`atan` — 이었다.
     * 거리 값은 필요 없고 「반경 안인가」 만 필요하다.
     * <ol>
     *   <li><b>위도 차이로 먼저 거른다.</b> 대권 거리는 위도 차이의 호({@code R·|Δφ|})보다 짧을 수 없다({@code a ≥ sin²(Δφ/2)}) — 그것만으로 이미 반경 밖이면 삼각함수 없이 탈락. 격자 버킷이 반경보다 훨씬 넓어 후보 대부분이 여기서 걸린다.</li>
     *   <li><b>{@code atan2} 를 없앤다.</b> {@code 2R·atan2(√a, √(1−a)) ≤ r ⟺ asin(√a) ≤ r/2R ⟺ a ≤ sin²(r/2R)}. {@code a} 는 {@code distanceKm} 과 같은 식으로 구한다.</li>
     *   <li><b>경계 근처는 옛 계산으로 되돌린다.</b> 임계값의 ±{@value #MARGIN} 상대 여유 안이면 {@code distanceKm} 으로 정확히 가른다 — 부동소수점 오차로 답이 달라질 수 없게.</li>
     * </ol>
     */
    public static boolean within(double lat1, double lng1, double lat2, double lng2, double radiusKm) {
        double dLat = Math.toRadians(lat2 - lat1);
        if (EARTH_RADIUS_KM * Math.abs(dLat) > radiusKm * (1 + MARGIN)) {
            return false;
        }
        double dLng = Math.toRadians(lng2 - lng1);
        double sinLat = Math.sin(dLat / 2);
        double sinLng = Math.sin(dLng / 2);
        double a = sinLat * sinLat + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLng * sinLng;
        double half = radiusKm / (2 * EARTH_RADIUS_KM);
        if (half >= Math.PI / 2) {                     // 반지름이 지구 둘레의 1/4 이상 — 이 근사의 전제 밖이다. 옛 계산으로
            return distanceKm(lat1, lng1, lat2, lng2) <= radiusKm;
        }
        double s = Math.sin(half);
        double threshold = s * s;
        if (a <= threshold * (1 - MARGIN)) {
            return true;
        }
        if (a >= threshold * (1 + MARGIN)) {
            return false;
        }
        return distanceKm(lat1, lng1, lat2, lng2) <= radiusKm;
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
