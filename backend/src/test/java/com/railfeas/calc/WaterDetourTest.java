package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.geo.Haversine;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 호수·저수지를 만나면 돌아가는 선형을 **제안**한다(바꾸는 것은 사용자다).
 *
 * 틀려도 그럴듯해 보이는 곳
 *   ① 돌아간 선의 한 구간이 여전히 물을 지난다 — 경유점만 물 밖이면 된다고 착각하기 쉽다
 *   ② 가늘고 긴 저수지(소양호처럼)를 끝까지 돌아가 수십 km 를 더한다 — 실제 철도는 다리로
 *      건넌다. 원래 구간의 1.5배를 넘으면 제안하지 않는다
 *   ③ 물이 없는 노선까지 건드린다
 */
class WaterDetourTest {

    /** 2.2km × 2.6km 네모난 호수 */
    private static final double[][] SQUARE = {
            {37.50, 127.00}, {37.52, 127.00}, {37.52, 127.03}, {37.50, 127.03}, {37.50, 127.00}};

    /** 폭 0.5km, 길이 22km 의 가늘고 긴 저수지 */
    private static final double[][] THIN = {
            {37.40, 127.010}, {37.60, 127.010}, {37.60, 127.016}, {37.40, 127.016}, {37.40, 127.010}};

    private static final double[] WEST = {37.51, 126.98};
    private static final double[] EAST = {37.51, 127.05};

    private static WaterDetour detour(String name, double[][] ring) {
        WaterBodies water = new WaterBodies("없는파일.geojson");
        water.add(name, List.<double[][]>of(ring));
        return new WaterDetour(water);
    }

    private static double km(List<double[]> line) {
        double sum = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1), b = line.get(i);
            sum += Haversine.distanceKm(a[0], a[1], b[0], b[1]);
        }
        return sum;
    }

    @Test
    @DisplayName("호수를 가로지르면 물을 지나지 않는 길로 돌아간다")
    void 돌아간다() {
        WaterDetour d = detour("가저수지", SQUARE);
        var plan = d.plan(List.of(WEST, EAST));

        assertThat(plan.crossings()).hasSize(1);
        var c = plan.crossings().get(0);
        assertThat(c.name()).isEqualTo("가저수지");
        assertThat(c.detoured()).isTrue();
        assertThat(plan.points().size()).isGreaterThan(2);
        // 양 끝은 사용자가 찍은 그대로다
        assertThat(plan.points().get(0)).containsExactly(WEST);
        assertThat(plan.points().get(plan.points().size() - 1)).containsExactly(EAST);
        // **새 선의 어느 구간도** 물을 지나지 않는다 — 경유점만 물 밖이면 안 된다
        WaterBodies water = new WaterBodies("없는파일.geojson");
        water.add("가저수지", List.<double[][]>of(SQUARE));
        for (int i = 1; i < plan.points().size(); i++) {
            assertThat(water.crossing(plan.points().get(i - 1), plan.points().get(i))).isEmpty();
        }
        double before = km(List.of(WEST, EAST));
        assertThat(km(plan.points())).isLessThanOrEqualTo(before * WaterDetour.MAX_RATIO);
        assertThat(c.addedKm()).isGreaterThan(0.0);
    }

    /**
     * 칸 중심만 보면 길이 물가에 딱 붙는다. 물 칸 둘레 한 칸(100m)을 더 막아 둔다 —
     * 실제 노선도 물가에 붙여 깔지 않고, 붙으면 곧게 이은 선이 모서리를 스친다.
     */
    @Test
    @DisplayName("모서리가 뾰족한 호수도 스치지 않고 물가에서 떨어져 돈다")
    void 물가에서_떨어진다() {
        // 마름모 — 여유 칸을 빼면 곧게 이은 선이 모서리를 스치고 경유점이 물가 7m 에 붙었다
        double[][] diamond = {{37.50, 127.015}, {37.515, 127.00}, {37.53, 127.015},
                {37.515, 127.03}, {37.50, 127.015}};
        var plan = detour("다저수지", diamond)
                .plan(List.of(new double[]{37.511, 126.98}, new double[]{37.517, 127.05}));

        WaterBodies water = new WaterBodies("없는파일.geojson");
        water.add("다저수지", List.<double[][]>of(diamond));
        for (int i = 1; i < plan.points().size(); i++) {
            assertThat(water.crossing(plan.points().get(i - 1), plan.points().get(i))).isEmpty();
        }
        List<double[]> via = plan.points().subList(1, plan.points().size() - 1);
        assertThat(via).isNotEmpty();
        for (double[] p : via) {
            assertThat(kmToEdge(p, diamond)).isGreaterThan(0.1);
        }
    }

    /** 점에서 다각형 경계까지 가장 가까운 거리(km) — 짧은 구간이라 평면으로 잰다 */
    private static double kmToEdge(double[] p, double[][] ring) {
        double kmLat = 110.574, kmLng = 111.320 * Math.cos(Math.toRadians(p[0]));
        double best = Double.MAX_VALUE;
        for (int i = 1; i < ring.length; i++) {
            double ax = (ring[i - 1][1] - p[1]) * kmLng, ay = (ring[i - 1][0] - p[0]) * kmLat;
            double bx = (ring[i][1] - p[1]) * kmLng, by = (ring[i][0] - p[0]) * kmLat;
            double dx = bx - ax, dy = by - ay;
            double t = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / (dx * dx + dy * dy)));
            best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy));
        }
        return best;
    }

    @Test
    @DisplayName("돌아가는 길이 1.5배를 넘으면 건넌다 — 선은 그대로 두고 이유를 말한다")
    void 길면_건넌다() {
        WaterDetour d = detour("나저수지", THIN);
        var plan = d.plan(List.of(WEST, EAST));

        assertThat(plan.points()).hasSize(2);
        assertThat(plan.crossings()).hasSize(1);
        assertThat(plan.crossings().get(0).detoured()).isFalse();
        assertThat(plan.crossings().get(0).reason()).contains("배");
    }

    @Test
    @DisplayName("물을 지나지 않는 노선은 건드리지 않는다")
    void 물이_없으면_그대로() {
        WaterDetour d = detour("가저수지", SQUARE);
        double[] north1 = {37.55, 126.98};
        double[] north2 = {37.55, 127.05};
        var plan = d.plan(List.of(north1, north2));

        assertThat(plan.crossings()).isEmpty();
        assertThat(plan.points()).hasSize(2);
    }

    @Test
    @DisplayName("한쪽 끝이 물 위에 찍혀 있어도 길을 찾는다")
    void 끝점이_물_위() {
        WaterDetour d = detour("가저수지", SQUARE);
        double[] inLake = {37.51, 127.025};
        var plan = d.plan(List.of(WEST, inLake));

        // 끝점은 사용자가 찍은 자리라 옮기지 않는다 — 길 찾기가 실패하지만 않으면 된다
        assertThat(plan.crossings()).hasSize(1);
        assertThat(plan.points().get(plan.points().size() - 1)).containsExactly(inLake);
    }

    /**
     * **실제 파일**로 본다. GeoJSON 은 [경도, 위도] 순서라 읽을 때 뒤집는데, 이걸 틀려도 오류 없이
     * **어떤 노선도 호수를 안 지나는 것처럼** 조용히 돈다. 위 테스트들은 손으로 만든 호수라 모른다.
     */
    @Test
    @DisplayName("저장소의 water_bodies.geojson 에서 신갈저수지를 알아본다")
    void 실제_파일() {
        WaterBodies water = new WaterBodies("../data/build/water_bodies.geojson");
        water.load();

        assertThat(water.size()).isGreaterThanOrEqualTo(100);
        // 용인 기흥 — 신갈저수지 한가운데를 동서로 가로지른다
        assertThat(water.crossing(new double[]{37.2391, 127.06}, new double[]{37.2391, 127.14}))
                .extracting(WaterBodies.Body::name).contains("신갈저수지");
        // 서울시청~강남 — 지나는 호수가 없다
        assertThat(water.crossing(new double[]{37.5663, 126.9779}, new double[]{37.4979, 127.0276}))
                .isEmpty();
    }

    @Test
    @DisplayName("선분이 호수를 지나는지 — 가로지르면 걸리고, 옆을 지나면 안 걸린다")
    void 지나는지() {
        WaterBodies water = new WaterBodies("없는파일.geojson");
        water.add("가저수지", List.<double[][]>of(SQUARE));

        assertThat(water.crossing(WEST, EAST)).extracting(WaterBodies.Body::name)
                .containsExactly("가저수지");
        assertThat(water.crossing(new double[]{37.53, 126.98}, new double[]{37.53, 127.05}))
                .isEmpty();
    }
}
