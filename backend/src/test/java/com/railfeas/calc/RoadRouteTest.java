package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 트램·BRT 는 기존 도로를 따라가고, 1.5배를 넘게 돌거나 길이 없으면 그 구간만 새 도로로 둔다(11-12 사용자 요청) */
class RoadRouteTest {

    private static RoadNetwork.Edge edge(int a, int b, double[]... coords) {
        return new RoadNetwork.Edge(a, b, 0, coords);
    }

    /** 남북 길(0-1) 하나와, 동서로 0.9km 떨어진 남북 길(3-4) — 둘을 북쪽 3.3km 위에서 잇는 길(1-4) */
    private static final RoadNetwork NET = RoadNetwork.ofEdges(List.of(
            edge(0, 1, new double[]{37.50, 126.90}, new double[]{37.53, 126.90}),
            edge(1, 4, new double[]{37.53, 126.90}, new double[]{37.53, 126.91}),
            edge(3, 4, new double[]{37.50, 126.91}, new double[]{37.53, 126.91})));

    @Test
    @DisplayName("기존 도로가 1.5배 안이면 도로를 따라간다 — 선은 도로 모양, 길이는 도로 길이")
    void 도로를_따라간다() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(new double[]{37.50, 126.90}, new double[]{37.53, 126.90}), NET);

        assertThat(p.pieces()).hasSize(1);
        assertThat(p.pieces().get(0).road()).isTrue();
        assertThat(p.roadKm()).isCloseTo(3.336, within(0.005));      // 위도 0.03° 하버사인
        assertThat(p.newKm()).isZero();
        assertThat(p.line().get(0)).containsExactly(37.50, 126.90);
    }

    @Test
    @DisplayName("기존 도로가 1.5배를 넘게 돌면 그 구간은 새 도로 — 직선 그대로, 이유를 적는다")
    void 너무_돌면_새_도로() {
        // 두 길의 남쪽 끝을 동서로 잇는다(0.88km) — 도로로는 북쪽으로 올라갔다 내려와 7.5km
        RoadRoute.Plan p = RoadRoute.plan(List.of(new double[]{37.50, 126.90}, new double[]{37.50, 126.91}), NET);

        assertThat(p.pieces()).hasSize(1);
        assertThat(p.pieces().get(0).road()).isFalse();
        assertThat(p.pieces().get(0).points()).hasSize(2);
        assertThat(p.pieces().get(0).reason()).contains("배").endsWith("새 도로").doesNotContain("물");
        assertThat(p.newKm()).isCloseTo(0.88, within(0.01));
    }

    @Test
    @DisplayName("구간마다 따로 정한다 — 도로 구간과 새 도로 구간이 이어진다")
    void 구간마다() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(new double[]{37.53, 126.90}, new double[]{37.50, 126.90},
                new double[]{37.50, 126.91}), NET);

        assertThat(p.pieces()).extracting(RoadRoute.Piece::road).containsExactly(true, false);
        assertThat(p.km()).isCloseTo(p.roadKm() + p.newKm(), within(1e-9));
        assertThat(p.line()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("찍은 점이 도로에서 떨어져 있으면 가까운 도로 위에서 시작한다 — 노선이 건물 안 점에서 도로까지 뚫고 가지 않는다(11-12)")
    void 끝점은_도로_위() {
        // 남북 길(126.90) 서쪽 0.0012° ≈ 106m 의 건물 안 점에서 시작해 길 위로 도착
        RoadRoute.Plan p = RoadRoute.plan(List.of(new double[]{37.50, 126.8988}, new double[]{37.53, 126.90}), NET);

        assertThat(p.pieces().get(0).road()).isTrue();
        assertThat(p.line().get(0)[1]).isCloseTo(126.90, within(1e-6));   // 시작이 도로 위로 옮겨졌다
        assertThat(p.line().get(0)[0]).isCloseTo(37.50, within(1e-6));
    }

    @Test
    @DisplayName("도로망 자료가 없으면 계획이 없다 — 예전처럼 그린 직선으로 센다")
    void 자료가_없으면() {
        assertThat(RoadRoute.plan(List.of(new double[]{37.50, 126.90}, new double[]{37.53, 126.90}),
                RoadNetwork.of(List.of()))).isNull();
    }

    @Test
    @DisplayName("저장 문자열로 바꿨다 되돌려도 같다 — 도로/새 도로 구별과 좌표")
    void 저장() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(new double[]{37.53, 126.90}, new double[]{37.50, 126.90},
                new double[]{37.50, 126.91}), NET);
        List<RoadRoute.Piece> back = RoadRoute.decode(RoadRoute.encode(p));

        assertThat(back).extracting(RoadRoute.Piece::road).containsExactly(true, false);
        assertThat(back.get(1).points().get(1)).containsExactly(37.50, 126.91);
        assertThat(RoadRoute.decode(null)).isEmpty();
    }

    // ── 도로 + 새 다리 혼합(11-19) — 돌아가는 길이 1.5배를 넘어도 직선이 물을 건너면 육지는 도로, 물 위만 새 도로

    private static double[] on(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    /** 위도 띠 {남쪽, 북쪽} 들에 동서로 놓인 강 — 남북으로 그은 선분이 그 띠를 지나는 토막. 입력 순서를 섞어도 돌려주는 대로 쓴다 */
    private static RoadRoute.WaterOnLine rivers(double[]... bands) {
        return (a, b) -> {
            java.util.List<double[][]> out = new java.util.ArrayList<>();
            for (double[] band : bands) {
                double t0 = (band[0] - a[0]) / (b[0] - a[0]), t1 = (band[1] - a[0]) / (b[0] - a[0]);
                double s = Math.max(0, Math.min(t0, t1)), e = Math.min(1, Math.max(t0, t1));
                if (e > s) {
                    out.add(new double[][]{on(a, b, s), on(a, b, e)});
                }
            }
            return out;
        };
    }

    private static final double[] A = {37.50, 126.90}, B = {37.53, 126.90};

    /** 강(위도 37.5105~37.5195)을 사이에 둔 두 남북 길 — 서로는 동쪽 5.3km 를 도는 다리로만 이어져 도로로는 14km(직선 3.3km 의 4배) */
    private static final RoadNetwork ACROSS_RIVER = RoadNetwork.ofEdges(List.of(
            edge(0, 1, new double[]{37.50, 126.90}, new double[]{37.51, 126.90}),
            edge(1, 2, new double[]{37.51, 126.90}, new double[]{37.51, 126.96}),
            new RoadNetwork.Edge(2, 3, RoadNetwork.BRIDGE, new double[][]{{37.51, 126.96}, {37.52, 126.96}}),
            edge(3, 4, new double[]{37.52, 126.96}, new double[]{37.52, 126.90}),
            edge(4, 5, new double[]{37.52, 126.90}, new double[]{37.53, 126.90})));

    @Test
    @DisplayName("돌아가는 길이 1.5배를 넘어도 직선이 강을 건너면 — 육지는 도로를 따라가고 강 위만 새 다리다(11-19)")
    void 육지는_도로_물_위만_새_다리() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(A, B), ACROSS_RIVER, rivers(new double[]{37.5105, 37.5195}));

        assertThat(p.pieces()).extracting(RoadRoute.Piece::road).containsExactly(true, false, true);
        assertThat(p.newKm()).as("새로 놓는 것은 강 양쪽 도로 끝 사이 한 토막(위도 0.01°)").isCloseTo(1.112, within(0.005));
        assertThat(p.roadKm()).isCloseTo(2.224, within(0.01));
        assertThat(p.pieces().get(1).points().get(0)).containsExactly(37.51, 126.90);
        assertThat(p.pieces().get(1).points().get(p.pieces().get(1).points().size() - 1)).containsExactly(37.52, 126.90);
        assertThat(p.pieces().get(1).reason()).as("왜 새 도로인가 — 도로로는 돌아야 해서 육지는 도로, 물 위만 새 다리").contains("배").contains("물 위만 새 다리");
        assertThat(p.pieces().get(0).reason()).as("도로 조각에는 이유가 없다").isNull();
        // 조각이 끊기지 않는다 — 앞 조각의 끝이 다음 조각의 시작이다
        for (int i = 1; i < p.pieces().size(); i++) {
            List<double[]> prev = p.pieces().get(i - 1).points(), next = p.pieces().get(i).points();
            assertThat(next.get(0)).containsExactly(prev.get(prev.size() - 1));
        }
    }

    @Test
    @DisplayName("한쪽 육지만 도로가 닿으면 그쪽만 도로 — 나머지는 다리에 이어 새 도로로 둔다")
    void 한쪽만_도로() {
        RoadNetwork southOnly = RoadNetwork.ofEdges(List.of(edge(0, 1, new double[]{37.50, 126.90}, new double[]{37.51, 126.90})));
        RoadRoute.Plan p = RoadRoute.plan(List.of(A, B), southOnly, rivers(new double[]{37.5105, 37.5195}));

        assertThat(p.pieces()).extracting(RoadRoute.Piece::road).containsExactly(true, false);
        List<double[]> tail = p.pieces().get(1).points();
        assertThat(tail.get(0)).containsExactly(37.51, 126.90);
        assertThat(tail.get(tail.size() - 1)).as("북쪽 끝은 도착점까지 직선").containsExactly(B);
        assertThat(p.pieces().get(1).reason()).as("도로로 못 이은 육지가 새 도로에 낀다고 적는다").contains("도로로 못 이은 육지");
        assertThat(p.newKm()).isCloseTo(2.224, within(0.01));
    }

    @Test
    @DisplayName("강 양쪽 어디에도 도로가 안 닿으면 예전처럼 직선 한 토막 — 혼합은 도로를 따라갈 육지가 있을 때만")
    void 도로가_안_닿으면_직선() {
        RoadNetwork far = RoadNetwork.ofEdges(List.of(edge(0, 1, new double[]{37.50, 127.20}, new double[]{37.51, 127.20})));
        RoadRoute.Plan p = RoadRoute.plan(List.of(A, B), far, rivers(new double[]{37.5105, 37.5195}));

        assertThat(p.pieces()).hasSize(1);
        assertThat(p.pieces().get(0).road()).isFalse();
        assertThat(p.pieces().get(0).points()).hasSize(2);
        assertThat(p.pieces().get(0).reason()).as("물을 건너는데도 직선인 까닭").contains("물을 건너지만 양쪽 육지도 도로로 이을 수 없어");
    }

    @Test
    @DisplayName("50m 도 안 되는 개울은 다리로 치지 않는다 — 그 때문에 구간을 쪼개지 않는다")
    void 작은_개울은_무시() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(A, B), ACROSS_RIVER, rivers(new double[]{37.5100, 37.5101}));    // 약 11m

        assertThat(p.pieces()).hasSize(1);
        assertThat(p.pieces().get(0).road()).isFalse();
        assertThat(p.pieces().get(0).points()).hasSize(2);
        assertThat(p.pieces().get(0).reason()).as("개울은 물로 치지 않으니 이유에도 물이 없다").doesNotContain("물");
    }

    @Test
    @DisplayName("강을 두 번 건너면 사이 육지도 도로다 — 도로·새 다리·도로·새 다리·도로 다섯 조각, 강 목록의 순서와 무관")
    void 강이_둘() {
        RoadNetwork three = RoadNetwork.ofEdges(List.of(
                edge(0, 1, new double[]{37.49, 126.90}, new double[]{37.50, 126.90}),
                edge(2, 3, new double[]{37.51, 126.90}, new double[]{37.52, 126.90}),
                edge(4, 5, new double[]{37.53, 126.90}, new double[]{37.55, 126.90})));
        // 북쪽 강을 먼저 적는다 — 선 위 순서(남→북)로 정렬해야 한다
        RoadRoute.Plan p = RoadRoute.plan(List.of(new double[]{37.49, 126.90}, new double[]{37.55, 126.90}), three,
                rivers(new double[]{37.5205, 37.5295}, new double[]{37.5005, 37.5095}));

        assertThat(p.pieces()).extracting(RoadRoute.Piece::road).containsExactly(true, false, true, false, true);
        assertThat(p.newKm()).as("다리 둘, 위도 0.01° 씩").isCloseTo(2 * 1.112, within(0.02));
    }

    @Test
    @DisplayName("혼합 조각도 저장 문자열로 바꿨다 되돌려도 같다")
    void 혼합_저장() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(A, B), ACROSS_RIVER, rivers(new double[]{37.5105, 37.5195}));
        List<RoadRoute.Piece> back = RoadRoute.decode(RoadRoute.encode(p));

        assertThat(back).extracting(RoadRoute.Piece::road).containsExactly(true, false, true);
        assertThat(back.get(1).reason()).as("이유도 저장했다 되돌려도 같다").isEqualTo(p.pieces().get(1).reason());
        assertThat(back.get(0).reason()).isNull();
    }

    // ── 물을 건너는 구간은 도로 문턱이 2.0배다(11-21 사용자 결정) — 새 다리는 1km 에 약 451억이라 돌아가더라도 기존 다리가 낫다

    /** 직선(37.50→37.53, 3.34km)을 동쪽 eastDeg° 로 돌아가는 ㄷ자 도로 — 동쪽 1.45km 면 6.24km 로 1.87배, 2.2km 면 2.3배 */
    private static RoadNetwork bracket(double eastDeg) {
        return RoadNetwork.ofEdges(List.of(
                edge(0, 1, new double[]{37.50, 126.90}, new double[]{37.50, 126.90 + eastDeg}),
                edge(1, 2, new double[]{37.50, 126.90 + eastDeg}, new double[]{37.53, 126.90 + eastDeg}),
                edge(2, 3, new double[]{37.53, 126.90 + eastDeg}, new double[]{37.53, 126.90})));
    }

    @Test
    @DisplayName("직선이 강을 건너면 도로가 1.5배를 넘어도 2.0배까지는 도로를 따라간다 — 새 다리보다 기존 다리가 낫다")
    void 물을_건너면_문턱이_2배() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(A, B), bracket(0.0165), rivers(new double[]{37.514, 37.518}));

        assertThat(p.pieces()).hasSize(1);
        assertThat(p.pieces().get(0).road()).as("1.87배 — 물을 건너니 도로").isTrue();
        assertThat(p.roadKm()).isCloseTo(6.24, within(0.05));
        assertThat(p.newKm()).isZero();
    }

    @Test
    @DisplayName("물을 안 건너면 문턱은 그대로 1.5배 — 같은 도로가 새 도로가 된다")
    void 물이_없으면_문턱은_1점5배() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(A, B), bracket(0.0165), rivers());

        assertThat(p.pieces()).hasSize(1);
        assertThat(p.pieces().get(0).road()).isFalse();
        assertThat(p.pieces().get(0).reason()).contains("1.9배");
    }

    @Test
    @DisplayName("50m 도 안 되는 개울은 물로 치지 않으니 문턱을 올리지 않는다")
    void 개울은_문턱을_안_올린다() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(A, B), bracket(0.0165), rivers(new double[]{37.5140, 37.5141}));    // 약 11m

        assertThat(p.pieces().get(0).road()).isFalse();
    }

    @Test
    @DisplayName("물을 건너도 2.0배를 넘으면 도로가 아니다 — 2.3배는 새 도로(또는 혼합)")
    void 물을_건너도_2배를_넘으면_새_도로() {
        RoadRoute.Plan p = RoadRoute.plan(List.of(A, B), bracket(0.025), rivers(new double[]{37.514, 37.518}));

        assertThat(p.pieces()).extracting(RoadRoute.Piece::road).doesNotContain(true);
        assertThat(p.pieces().get(0).reason()).contains("2.3배");
    }

    @Test
    @DisplayName("깨진 조각은 건너뛰고 나머지를 읽는다 — 한 조각 때문에 시나리오 상세 조회가 통째로 실패하지 않는다")
    void 깨진_조각은_건너뛴다() {
        // 둘째 조각: 이유가 탭이 아니라 공백 뒤에 붙었다(편집기가 탭을 공백으로 바꾼 저장) — 좌표가 아닌 토큰이 섞였다
        // 셋째 조각: 점이 하나뿐이다(선이 아니다)
        List<RoadRoute.Piece> back = RoadRoute.decode(
                "R 37.50000,126.90000 37.51000,126.90000|N 37.51000,126.90000 37.52000,126.90000 기존 도로가 1.7배라|N 37.52000,126.90000|"
                        + "R 37.52000,126.90000 37.53000,126.90000");

        assertThat(back).extracting(RoadRoute.Piece::road).containsExactly(true, true);
        assertThat(back.get(1).points().get(1)).containsExactly(37.53, 126.90);
    }

    @Test
    @DisplayName("소스에 실제 탭 문자를 넣지 않는다 — 이유 구분자는 '\t' 이스케이프다(편집기가 탭을 공백으로 바꾸면 저장 형식이 깨진다)")
    void 소스에_탭_문자가_없다() throws java.io.IOException {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/railfeas/calc/RoadRoute.java"));

        assertThat(src).doesNotContain("	");
    }

    @Test
    @DisplayName("이유 없이 저장된 옛 노선도 읽는다 — 탭이 없는 저장 문자열(11-19 이전)")
    void 옛_저장은_이유_없이() {
        List<RoadRoute.Piece> back = RoadRoute.decode("R 37.50000,126.90000 37.51000,126.90000|N 37.51000,126.90000 37.52000,126.90000");

        assertThat(back).extracting(RoadRoute.Piece::road).containsExactly(true, false);
        assertThat(back.get(1).reason()).isNull();
        assertThat(back.get(1).points()).hasSize(2);
    }
}
