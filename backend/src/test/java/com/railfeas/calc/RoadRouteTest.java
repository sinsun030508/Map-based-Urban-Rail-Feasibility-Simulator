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
        assertThat(p.pieces().get(0).reason()).contains("배");
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
}
