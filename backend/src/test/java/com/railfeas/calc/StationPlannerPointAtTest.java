package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * **`pointAt` 은 선형 탐색 때나 이분 탐색 때나 같은 점을 돌려준다**(11-22 JFR 재프로파일). 최악 노선 계산의 CPU 샘플 14% 가 `pointAt` 한 줄이었다 — 호출마다 경로를 첫 꼭짓점부터 훑는데,
 * 도로를 따라가는 경로는 꼭짓점이 수천 개이고 역 하나를 찾을 때 후보 위치를 수십 번 샘플링해 (후보 수) × (꼭짓점 수) 로 제곱이다. 누적 거리는 정렬돼 있으니 이분 탐색으로 바꿨다.
 * 정의는 **「`d <= cum[i]` 를 처음 만족하는 i(≥1), 없으면 마지막」** — 같은 누적 거리(길이 0 구간)가 이어질 때 **첫** 인덱스를 잡는 것까지 같아야 한다.
 */
class StationPlannerPointAtTest {

    /** 옛 구현 그대로(선형 탐색) — 새 구현이 이것과 같음을 증명하는 기준 */
    private static double[] reference(List<double[]> route, List<Double> cum, double distance) {
        double total = cum.get(cum.size() - 1);
        double d = Math.max(0, Math.min(total, distance));
        for (int i = 1; i < cum.size(); i++) {
            if (d <= cum.get(i) || i == cum.size() - 1) {
                double segment = cum.get(i) - cum.get(i - 1);
                double ratio = segment <= 0 ? 0 : (d - cum.get(i - 1)) / segment;
                double[] a = route.get(i - 1);
                double[] b = route.get(i);
                return new double[]{a[0] + (b[0] - a[0]) * ratio, a[1] + (b[1] - a[1]) * ratio};
            }
        }
        return route.get(route.size() - 1);
    }

    private static List<double[]> routeOf(Random rnd, int n) {
        List<double[]> route = new ArrayList<>();
        double lat = 37.5, lng = 127.0;
        for (int i = 0; i < n; i++) {
            route.add(new double[]{lat, lng});
            // 가끔 제자리(길이 0 구간)를 섞는다 — 같은 누적 거리가 이어지는 경우
            if (rnd.nextInt(6) != 0) {
                lat += (rnd.nextDouble() - 0.3) * 0.01;
                lng += (rnd.nextDouble() - 0.3) * 0.01;
            }
        }
        return route;
    }

    private static List<Double> cumulativeOf(List<double[]> route) {
        List<Double> cum = new ArrayList<>();
        cum.add(0.0);
        for (int i = 1; i < route.size(); i++) {
            double[] a = route.get(i - 1), b = route.get(i);
            cum.add(cum.get(i - 1) + com.railfeas.geo.Haversine.distanceKm(a[0], a[1], b[0], b[1]));
        }
        return cum;
    }

    @Test
    @DisplayName("무작위 경로 400개 × 위치 200개에서 옛 선형 탐색과 같은 점이다 — 길이 0 구간 포함")
    void agreesWithTheLinearScan() {
        Random rnd = new Random(20261123L);
        int zeroSegments = 0;
        for (int t = 0; t < 400; t++) {
            List<double[]> route = routeOf(rnd, 2 + rnd.nextInt(60));
            List<Double> cum = cumulativeOf(route);
            for (int i = 1; i < cum.size(); i++) {
                if (cum.get(i).equals(cum.get(i - 1))) {
                    zeroSegments++;
                }
            }
            double total = cum.get(cum.size() - 1);
            for (int k = 0; k < 200; k++) {
                double d = (rnd.nextDouble() * 1.4 - 0.2) * total;      // 앞(음수)·끝 너머도 섞는다
                assertThat(StationPlanner.pointAt(route, cum, d)).as("경로 %d 위치 %s", t, d).containsExactly(reference(route, cum, d));
            }
        }
        assertThat(zeroSegments).as("길이 0 구간이 실제로 섞여 있어야 시험이 된다").isGreaterThan(100);
    }

    @Test
    @DisplayName("경계값에서 같다 — 꼭짓점의 누적 거리 그대로·그 ulp 앞뒤·0·끝·음수·초과")
    void agreesExactlyAtTheBoundaries() {
        Random rnd = new Random(5L);
        for (int t = 0; t < 200; t++) {
            List<double[]> route = routeOf(rnd, 2 + rnd.nextInt(40));
            List<Double> cum = cumulativeOf(route);
            double total = cum.get(cum.size() - 1);
            List<Double> probes = new ArrayList<>(List.of(0.0, -1.0, total, total + 1, Double.MIN_VALUE));
            for (double c : cum) {
                probes.add(c);
                probes.add(Math.nextUp(c));
                probes.add(Math.nextDown(c));
            }
            for (double d : probes) {
                assertThat(StationPlanner.pointAt(route, cum, d)).as("경로 %d d=%s", t, d).containsExactly(reference(route, cum, d));
            }
        }
    }

    @Test
    @DisplayName("꼭짓점이 둘뿐이어도, 길이가 0 인 경로여도 같다")
    void tinyRoutes() {
        List<double[]> two = List.of(new double[]{37.5, 127.0}, new double[]{37.6, 127.1});
        List<Double> cum2 = cumulativeOf(two);
        for (double d : new double[]{-5, 0, cum2.get(1) / 2, cum2.get(1), cum2.get(1) + 5}) {
            assertThat(StationPlanner.pointAt(two, cum2, d)).containsExactly(reference(two, cum2, d));
        }
        List<double[]> same = List.of(new double[]{37.5, 127.0}, new double[]{37.5, 127.0}, new double[]{37.5, 127.0});
        List<Double> cumSame = cumulativeOf(same);
        for (double d : new double[]{-1, 0, 1}) {
            assertThat(StationPlanner.pointAt(same, cumSame, d)).containsExactly(reference(same, cumSame, d));
        }
    }
}
