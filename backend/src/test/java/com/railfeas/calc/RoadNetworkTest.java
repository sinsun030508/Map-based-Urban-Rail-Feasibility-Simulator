package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.railfeas.geo.Haversine;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 큰길 도로망 — ① 정차역을 가장 가까운 큰길 「선분 위」 점으로 옮기고(다리·터널·진출입로는 아니다, 0.3km 안에 없으면 0.6km 까지 넓힌다),
 * ② 트램·BRT 가 기존 도로를 따라가는 가장 짧은 길을 찾는다(11-12)
 */
class RoadNetworkTest {

    /** 노량진 둘레 동서 큰길 하나 — 꼭짓점은 양 끝(1.8km 떨어져)뿐이다 */
    private static final double[][] EAST_WEST = {{37.5100, 126.9300}, {37.5100, 126.9500}};

    private static RoadNetwork.Edge edge(int a, int b, int flags, double[]... coords) {
        return new RoadNetwork.Edge(a, b, flags, coords);
    }

    @Test
    @DisplayName("가장 가까운 큰길의 선분 위 점으로 옮긴다 — 꼭짓점이 멀어도")
    void 선분_위로_옮긴다() {
        RoadNetwork r = RoadNetwork.of(List.<double[][]>of(EAST_WEST));
        double[] at = r.snap(new double[]{37.5110, 126.9400});   // 길 북쪽 약 110m

        assertThat(at[0]).isCloseTo(37.5100, within(1e-6));
        assertThat(at[1]).isCloseTo(126.9400, within(1e-6));
    }

    @Test
    @DisplayName("0.3km 안에 길이 없으면 0.6km 까지 넓혀 붙인다 — 빽빽한 주택가에서 역이 건물 블록에 남지 않게(11-13), 0.6km 밖이면 그대로")
    void 먼_길은_넓혀서_붙인다() {
        RoadNetwork r = RoadNetwork.of(List.<double[][]>of(EAST_WEST));
        // 약 440m — 예전 한도(0.3km)로는 그대로 건물 안에 남았다(고강본동역 474m)
        double[] mid = {37.5140, 126.9400};
        assertThat(r.snap(mid)[0]).isCloseTo(37.5100, within(1e-6));
        assertThat(r.nearest(37.5124, 126.9400, RoadNetwork.MAX_SNAP_KM)).isPresent();   // 약 265m 는 가까운 한도 안
        assertThat(r.nearest(37.5140, 126.9400, RoadNetwork.MAX_SNAP_KM)).isEmpty();      // 440m 는 가까운 한도 밖

        // 약 730m — 먼 한도(0.6km)도 넘는다. 자리가 그대로다(산·논밭)
        double[] far = {37.5165, 126.9400};
        assertThat(r.snap(far)).isSameAs(far);
    }

    @Test
    @DisplayName("먼 한도에서도 큰길과 집산도로 중 가까운 쪽 — 큰길 우선은 가까운 한도(0.3km) 안에서만")
    void 먼_한도는_가까운_쪽() {
        double[][] minor = {{37.5125, 126.9300}, {37.5125, 126.9500}};          // 집산도로 약 280m
        double[][] major = {{37.5170, 126.9300}, {37.5170, 126.9500}};          // 큰길 약 780m
        RoadNetwork both = RoadNetwork.ofEdges(List.of(
                new RoadNetwork.Edge(0, 1, RoadNetwork.MINOR, minor), new RoadNetwork.Edge(2, 3, 0, major)));
        // 집산도로가 0.3km 안 → 큰길이 없어도 집산도로
        assertThat(both.snap(new double[]{37.5100, 126.9400})[0]).isCloseTo(37.5125, within(1e-6));
        // 큰길 460m·집산도로 530m — 둘 다 0.3km 밖이라 먼 한도에서 가까운 쪽(큰길)
        RoadNetwork wide = RoadNetwork.ofEdges(List.of(
                new RoadNetwork.Edge(0, 1, RoadNetwork.MINOR, new double[][]{{37.5148, 126.9300}, {37.5148, 126.9500}}),
                new RoadNetwork.Edge(2, 3, 0, new double[][]{{37.5042, 126.9300}, {37.5042, 126.9500}})));
        assertThat(wide.snap(new double[]{37.5100, 126.9400})[0]).isCloseTo(37.5148, within(1e-6));
    }

    @Test
    @DisplayName("둘 중 가까운 길을 고른다 — 격자 칸 경계를 넘어도")
    void 가까운_길() {
        double[][] north = {{37.5130, 126.9300}, {37.5130, 126.9500}};
        RoadNetwork r = RoadNetwork.of(List.of(EAST_WEST, north));
        double[] at = r.snap(new double[]{37.5118, 126.9400});   // 북쪽 길 130m, 남쪽 길 200m

        assertThat(at[0]).isCloseTo(37.5130, within(1e-6));
        assertThat(Haversine.distanceKm(37.5118, 126.94, at[0], at[1])).isLessThan(0.14);
    }

    @Test
    @DisplayName("다리·터널·진출입로 위로는 역을 옮기지 않는다 — 더 가까워도 땅 위 큰길로")
    void 다리_위엔_역이_없다() {
        RoadNetwork r = RoadNetwork.ofEdges(List.of(
                edge(0, 1, RoadNetwork.BRIDGE, new double[]{37.5105, 126.9300}, new double[]{37.5105, 126.9500}),
                edge(2, 3, 0, EAST_WEST)));
        double[] at = r.snap(new double[]{37.5108, 126.9400});   // 다리 33m, 땅 위 길 89m

        assertThat(at[0]).isCloseTo(37.5100, within(1e-6));
    }

    @Test
    @DisplayName("보조간선 이상을 먼저 붙인다 — 집산도로가 더 가까워도, 0.3km 안에 큰길이 없을 때만 집산도로(11-12)")
    void 큰_길_먼저() {
        double[][] minor = {{37.5115, 126.9300}, {37.5115, 126.9500}};
        RoadNetwork both = RoadNetwork.ofEdges(List.of(
                edge(0, 1, RoadNetwork.MINOR, minor), edge(2, 3, 0, EAST_WEST)));
        double[] p = {37.5120, 126.9400};                          // 집산도로 55m, 보조간선 221m

        assertThat(both.snap(p)[0]).isCloseTo(37.5100, within(1e-6));
        RoadNetwork onlyMinor = RoadNetwork.ofEdges(List.of(edge(0, 1, RoadNetwork.MINOR, minor)));
        assertThat(onlyMinor.snap(p)[0]).isCloseTo(37.5115, within(1e-6));
    }

    /**
     * ㄷ자 도로망 — 0(서남) ─ 1(서북) ─ 2(동북) ─ 3(동남). 0 과 3 사이(남쪽)는 길이 없다(강이라고 치자).
     * 0 → 3 은 북쪽으로 돌아가야 한다
     */
    private static RoadNetwork bracket() {
        return RoadNetwork.ofEdges(List.of(
                edge(0, 1, 0, new double[]{37.50, 126.90}, new double[]{37.51, 126.90}),
                edge(1, 2, RoadNetwork.BRIDGE, new double[]{37.51, 126.90}, new double[]{37.51, 126.91}),
                edge(3, 2, 0, new double[]{37.50, 126.91}, new double[]{37.505, 126.91}, new double[]{37.51, 126.91})));
    }

    @Test
    @DisplayName("기존 도로로 가는 가장 짧은 길 — 다리도 지나고, 간선 방향이 거꾸로여도 잇는다")
    void 길을_찾는다() {
        var path = bracket().route(new double[]{37.4999, 126.9000}, new double[]{37.4999, 126.9100}).orElseThrow();

        double sides = Haversine.distanceKm(37.50, 126.90, 37.51, 126.90) * 2 + Haversine.distanceKm(37.51, 126.90, 37.51, 126.91);
        assertThat(path.km()).isCloseTo(sides + 0.011 * 2, within(0.005));      // 양 끝에서 교차점까지 약 11m 씩
        assertThat(path.points().get(0)).containsExactly(37.4999, 126.9000);
        assertThat(path.points().get(path.points().size() - 1)).containsExactly(37.4999, 126.9100);
        // 북쪽으로 돈다 — 가운데 점이 북쪽(37.51)에 있다
        assertThat(path.points()).anySatisfy(p -> assertThat(p[0]).isCloseTo(37.51, within(1e-9)));
        assertThat(path.points()).anySatisfy(p -> assertThat(p[0]).isCloseTo(37.505, within(1e-9)));
    }

    @Test
    @DisplayName("이어지지 않았거나 1km 안에 교차점이 없으면 길이 없다")
    void 길이_없다() {
        RoadNetwork r = RoadNetwork.ofEdges(List.of(
                edge(0, 1, 0, new double[]{37.50, 126.90}, new double[]{37.51, 126.90}),
                edge(2, 3, 0, new double[]{37.50, 126.95}, new double[]{37.51, 126.95})));
        assertThat(r.route(new double[]{37.50, 126.90}, new double[]{37.50, 126.95})).isEmpty();
        assertThat(bracket().route(new double[]{37.40, 126.90}, new double[]{37.50, 126.91})).isEmpty();
    }

    @Test
    @DisplayName("파일을 읽는다(gzip, 한 줄에 간선 하나 — A B 표시 좌표…) — 없으면 꺼지고 자리를 그대로 둔다")
    void 파일(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("road_graph.csv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(f))) {
            out.write(("0 1 0 37.51000,126.93000 37.51000,126.95000\n"
                    + "1 2 1 37.51000,126.95000 37.52000,126.95000\n").getBytes(StandardCharsets.US_ASCII));
        }
        RoadNetwork r = new RoadNetwork(f.toString());
        r.load();
        assertThat(r.available()).isTrue();
        assertThat(r.snap(new double[]{37.5110, 126.9400})[0]).isCloseTo(37.5100, within(1e-5));
        assertThat(r.route(new double[]{37.51, 126.93}, new double[]{37.52, 126.95})).isPresent();

        RoadNetwork none = new RoadNetwork(dir.resolve("없는파일.csv.gz").toString());
        none.load();
        double[] p = {37.5110, 126.9400};
        assertThat(none.available()).isFalse();
        assertThat(none.snap(p)).isSameAs(p);
        assertThat(none.route(p, new double[]{37.52, 126.95})).isEmpty();
    }
}
