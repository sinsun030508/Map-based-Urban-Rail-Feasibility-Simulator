package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.geo.Haversine;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 돌아가는 안 — 가짜 지형 위 10km 남북 직선(36.0°N, 128.0°E 에서 북쪽으로).
 * 지형은 (s, x) km 로 그린다 — s 는 노선을 따라 북쪽, x 는 동쪽.
 */
class MountainDetourTest {

    private static final double LAT0 = 36.0, LNG0 = 128.0;
    private static final double KM_LAT = 111.195, KM_LNG = 111.195 * Math.cos(Math.toRadians(LAT0));
    private static final List<double[]> ROUTE = List.of(new double[]{LAT0, LNG0}, new double[]{LAT0 + 10 / KM_LAT, LNG0});

    private interface Ground {
        double at(double s, double x);
    }

    private static Elevation terrain(Ground g) {
        return (lat, lng) -> OptionalDouble.of(g.at((lat - LAT0) * KM_LAT, (lng - LNG0) * KM_LNG));
    }

    /** 가운데(s=5)에 높이 400m · 반지름 2km 원뿔. 나머지는 해발 2m 평지 */
    private static double cone(double s, double x, double cx) {
        double r = Math.hypot(s - 5, x - cx);
        return Math.max(2, 400 * (1 - r / 2));
    }

    private static double km(List<double[]> line) {
        double sum = 0;
        for (int i = 1; i < line.size(); i++) {
            sum += Haversine.distanceKm(line.get(i - 1)[0], line.get(i - 1)[1], line.get(i)[0], line.get(i)[1]);
        }
        return sum;
    }

    @Test
    @DisplayName("원뿔 산은 돌아간다 — 돌아간 길에는 다시 산이 없다")
    void coneDetours() {
        Elevation e = terrain((s, x) -> cone(s, x, 0));
        var plan = MountainDetour.plan(ROUTE, e);

        assertThat(plan.mountains()).hasSize(1);
        var m = plan.mountains().get(0);
        assertThat(m.index()).isEqualTo(1);
        assertThat(m.detoured()).isTrue();
        assertThat(m.addedKm()).isPositive();
        assertThat(m.tunnelKm()).isPositive();
        assertThat(MountainProfile.sections(plan.points(), e)).isEmpty();
        assertThat(km(plan.points())).isLessThanOrEqualTo(10 * MountainDetour.MAX_RATIO);
        assertThat(km(plan.points()) - 10).isCloseTo(m.addedKm(), org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    @DisplayName("산맥이 통째로 막으면 못 돌아간다 — 점은 그대로")
    void ridgeBlocks() {
        var plan = MountainDetour.plan(ROUTE, terrain((s, x) -> Math.max(2, 400 * (1 - Math.abs(s - 5) / 2))));

        assertThat(plan.mountains()).hasSize(1);
        var m = plan.mountains().get(0);
        assertThat(m.detoured()).isFalse();
        assertThat(m.reason()).contains("돌아갈 길");
        assertThat(plan.points()).containsExactlyElementsOf(ROUTE);
    }

    @Test
    @DisplayName("동서로 길게 뻗은 산은 끝까지 돌면 1.5배를 넘는다 — 제안하지 않는다")
    void tooFar() {
        // 남북 폭 4km, 동서로 ±12km — 돌아가려면 옆으로 12km 를 다녀와야 한다
        var plan = MountainDetour.plan(ROUTE, terrain((s, x) ->
                Math.abs(x) > 12 ? 2 : Math.max(2, 400 * (1 - Math.abs(s - 5) / 2))));

        var m = plan.mountains().get(0);
        assertThat(m.detoured()).isFalse();
        assertThat(m.reason()).contains("배");
        assertThat(plan.points()).containsExactlyElementsOf(ROUTE);
    }

    @Test
    @DisplayName("산이 없으면 빈 목록 — 점은 그대로")
    void flat() {
        var plan = MountainDetour.plan(ROUTE, terrain((s, x) -> 50));

        assertThat(plan.mountains()).isEmpty();
        assertThat(plan.points()).containsExactlyElementsOf(ROUTE);
    }

    @Test
    @DisplayName("바다 위로는 돌아가지 않는다 — 해안의 산은 뭍 쪽으로 돈다")
    void notOverSea() {
        // 원뿔 중심이 서쪽으로 0.5km 치우쳐 동쪽으로 도는 편이 짧지만, 동쪽(x > 0.3km)은 바다(0m)다
        Elevation e = terrain((s, x) -> x > 0.3 ? 0 : cone(s, x, -0.5));
        var plan = MountainDetour.plan(ROUTE, e);

        var m = plan.mountains().get(0);
        assertThat(m.detoured()).isTrue();
        for (double[] p : plan.points()) {
            assertThat((p[1] - LNG0) * KM_LNG).isLessThan(0.3);
        }
    }

    @Test
    @DisplayName("돌아가는 편이 손해면 뚫는다 — 점은 그대로, 이유를 적는다")
    void costlierDetourTunnels() {
        Elevation e = terrain((s, x) -> cone(s, x, 0));
        var plan = MountainDetour.plan(ROUTE, e, 0.01);

        var m = plan.mountains().get(0);
        assertThat(m.detoured()).isFalse();
        assertThat(m.costlier()).isTrue();
        assertThat(m.addedKm()).isZero();
        assertThat(m.reason()).contains("뚫");
        assertThat(plan.points()).containsExactlyElementsOf(ROUTE);
    }

    @Test
    @DisplayName("돌아가는 편이 이득이면 돈다")
    void cheaperDetourStays() {
        var m = MountainDetour.plan(ROUTE, terrain((s, x) -> cone(s, x, 0)), 100.0).mountains().get(0);

        assertThat(m.detoured()).isTrue();
        assertThat(m.costlier()).isFalse();
    }

    @Test
    @DisplayName("구간마다 따로 견준다 — 한 노선에서 한 곳은 돌고 한 곳은 뚫는다")
    void decidesPerSection() {
        // 20km 노선. 앞 산은 노선 한가운데, 뒤 산은 옆으로 비껴 조금만 돌면 된다
        List<double[]> route = List.of(new double[]{LAT0, LNG0}, new double[]{LAT0 + 20 / KM_LAT, LNG0});
        Elevation e = terrain((s, x) -> Math.max(2, Math.max(
                400 * (1 - Math.hypot(s - 5, x) / 2),
                400 * (1 - Math.hypot(s - 15, x - 1.5) / 2))));

        var free = MountainDetour.plan(route, e).mountains();
        assertThat(free).hasSize(2).allMatch(MountainDetour.Mountain::detoured);
        double r1 = free.get(0).addedKm() / free.get(0).tunnelKm();
        double r2 = free.get(1).addedKm() / free.get(1).tunnelKm();
        assertThat(r1).isNotCloseTo(r2, org.assertj.core.data.Percentage.withPercentage(5));

        // 두 비율 사이의 손익분기 — 정확히 한 곳만 돌아야 한다
        var mixed = MountainDetour.plan(route, e, (r1 + r2) / 2).mountains();
        assertThat(mixed).filteredOn(MountainDetour.Mountain::detoured).hasSize(1);
        assertThat(mixed).filteredOn(MountainDetour.Mountain::costlier).hasSize(1);
    }
}
