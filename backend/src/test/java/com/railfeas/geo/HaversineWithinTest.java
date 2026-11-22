package com.railfeas.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * **`within` 은 `distanceKm(...) <= radiusKm` 과 항상 같은 답이어야 한다**(11-22 JFR 프로파일). 최악 노선 계산 6초의 CPU 샘플 57% 가 `PopulationIndex.near` 의 하버사인(`atan2`·`atan` —
 * JDK 의 느린 `StrictMath`)이었다. 거리 값은 필요 없고 「반경 안인가」 만 필요해, 위도 차이로 먼저 거르고 `atan2` 없이 `a ≤ sin²(r/2R)` 로 가른다. 경계에 아주 가까운 것만 옛 계산으로 되돌린다.
 * 결과가 **한 칸도** 달라지면 인구·역 배치가 바뀌므로, 무작위 쌍과 **경계 바로 위·아래(ulp)** 에서 같음을 증명한다.
 */
class HaversineWithinTest {

    private static final double[] RADII = {0.05, 0.3, 0.5, 1.0, 3.0, 10.0};

    @Test
    @DisplayName("무작위 20만 쌍에서 distanceKm <= r 과 같다 — 한국 범위 위도·경도, 여러 반경")
    void agreesWithDistanceOnRandomPairs() {
        Random rnd = new Random(20261122L);
        int disagreements = 0, inside = 0;
        for (int i = 0; i < 200_000; i++) {
            double lat = 33.0 + rnd.nextDouble() * 6.0;
            double lng = 124.0 + rnd.nextDouble() * 8.0;
            // 반경 근처에 몰리도록 작은 오프셋(대부분 0.2° 이내) — 경계 가까운 쌍이 많아야 시험이 의미 있다
            double lat2 = lat + (rnd.nextDouble() - 0.5) * 0.04;
            double lng2 = lng + (rnd.nextDouble() - 0.5) * 0.04;
            double r = RADII[rnd.nextInt(RADII.length)];
            boolean expected = Haversine.distanceKm(lat, lng, lat2, lng2) <= r;
            if (expected) {
                inside++;
            }
            if (Haversine.within(lat, lng, lat2, lng2, r) != expected) {
                disagreements++;
            }
        }
        assertThat(inside).as("안쪽 쌍이 충분히 섞여 있어야 시험이 된다").isBetween(20_000, 180_000);
        assertThat(disagreements).isZero();
    }

    @Test
    @DisplayName("경계 바로 위·아래(ulp 단위)에서도 같다 — 같은 위도의 동서 방향·같은 경도의 남북 방향 이분 탐색")
    void agreesJustAroundTheBoundary() {
        for (double r : RADII) {
            for (double lat : new double[]{33.1, 35.5, 37.57, 38.6}) {
                // 남북: 같은 경도에서 위도만 올려 거리가 정확히 r 이 되는 경계를 찾는다
                double lo = lat, hi = lat + 1.0;
                for (int k = 0; k < 200; k++) {
                    double mid = (lo + hi) / 2;
                    if (Haversine.distanceKm(lat, 127.0, mid, 127.0) <= r) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                checkAround(lat, 127.0, lo, 127.0, r);
                // 동서: 같은 위도에서 경도만 늘린다
                double l2 = 127.0, h2 = 128.0;
                for (int k = 0; k < 200; k++) {
                    double mid = (l2 + h2) / 2;
                    if (Haversine.distanceKm(lat, 127.0, lat, mid) <= r) {
                        l2 = mid;
                    } else {
                        h2 = mid;
                    }
                }
                checkAround(lat, 127.0, lat, l2, r);
            }
        }
    }

    /** 경계점의 앞뒤 ulp 수십 개에서 within 이 distanceKm <= r 과 같다 */
    private static void checkAround(double lat1, double lng1, double lat2, double lng2, double r) {
        double a = lat2, b = lng2, c = lat2, d = lng2;
        for (int i = 0; i < 40; i++) {
            for (double[] p : new double[][]{{a, lng2}, {lat2, b}, {c, lng2}, {lat2, d}}) {
                boolean expected = Haversine.distanceKm(lat1, lng1, p[0], p[1]) <= r;
                assertThat(Haversine.within(lat1, lng1, p[0], p[1], r))
                        .as("r=%s 점=(%s, %s) 기준=(%s, %s)", r, p[0], p[1], lat1, lng1).isEqualTo(expected);
            }
            a = Math.nextUp(a);
            b = Math.nextUp(b);
            c = Math.nextDown(c);
            d = Math.nextDown(d);
        }
    }

    /**
     * **반경을 거리 값의 ulp 이웃으로** 잡는다 — 좌표를 ulp 씩 움직이면 거리는 상대 1e-12 씩 변해, 안전 여유(1e-9)가 지키는 부동소수점 오차 영역(상대 ~1e-16)에 닿지 못한다.
     * 여유·되돌림을 없애 보면(`a <= threshold` 만) 위 두 시험은 그대로 통과했다(돌연변이가 살아남았다) — 이 시험이 그것을 잡는다.
     */
    @Test
    @DisplayName("반경을 거리 값 자체와 그 ulp 이웃으로 두어도 같다 — 안전 여유가 지키는 경계를 정확히 겨냥한다")
    void agreesWhenTheRadiusIsTheDistanceItself() {
        Random rnd = new Random(99L);
        for (int i = 0; i < 100_000; i++) {
            double lat = 33.0 + rnd.nextDouble() * 6.0, lng = 124.0 + rnd.nextDouble() * 8.0;
            double lat2 = lat + (rnd.nextDouble() - 0.5) * 0.04, lng2 = lng + (rnd.nextDouble() - 0.5) * 0.04;
            double d = Haversine.distanceKm(lat, lng, lat2, lng2);
            for (double r : new double[]{Math.nextDown(d), d, Math.nextUp(d), d * (1 - 1e-12), d * (1 + 1e-12)}) {
                assertThat(Haversine.within(lat, lng, lat2, lng2, r))
                        .as("r=%s d=%s 쌍=(%s,%s)-(%s,%s)", r, d, lat, lng, lat2, lng2).isEqualTo(d <= r);
            }
        }
    }

    @Test
    @DisplayName("같은 점은 안이고, 위도 차이만으로 이미 먼 점은 밖이다")
    void trivialCases() {
        assertThat(Haversine.within(37.5, 127.0, 37.5, 127.0, 0.0)).isTrue();       // 거리 0 <= 0
        assertThat(Haversine.within(37.5, 127.0, 37.5, 127.0, 1.0)).isTrue();
        assertThat(Haversine.within(37.5, 127.0, 37.6, 127.0, 1.0)).isFalse();      // 위도 0.1° ≈ 11km
        assertThat(Haversine.within(37.5, 127.0, 37.5, 127.0001, 1.0)).isTrue();    // 경도 0.0001° ≈ 9m
    }

    @Test
    @DisplayName("반경이 반지름이 아주 커도 같다 — 지구 반대편까지는 아니지만 수백 km")
    void largeRadiiStillAgree() {
        Random rnd = new Random(7L);
        for (int i = 0; i < 20_000; i++) {
            double lat = 33.0 + rnd.nextDouble() * 6.0, lng = 124.0 + rnd.nextDouble() * 8.0;
            double lat2 = 33.0 + rnd.nextDouble() * 6.0, lng2 = 124.0 + rnd.nextDouble() * 8.0;
            double r = 50 + rnd.nextDouble() * 500;
            assertThat(Haversine.within(lat, lng, lat2, lng2, r)).isEqualTo(Haversine.distanceKm(lat, lng, lat2, lng2) <= r);
        }
    }
}
