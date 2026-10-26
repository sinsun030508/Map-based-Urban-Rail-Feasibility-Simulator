package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 물 위 구간 길이 — 교량 할증을 못 넣은 몫이 얼마나 되는지 드러내려고 잰다
 * (`cost_standard` 는 수단 × 구조뿐이라 한강을 건너는 1km 와 평지 1km 가 같은 값이다).
 *
 * 강은 **우회하지 않는다**(건너야 한다) — 우회 판정에는 들어가지 않고 길이만 잰다.
 */
class WaterBodiesTest {

    /** 동서 0.03° (위도 37.51 에서 약 2.65km) × 남북 0.02° */
    private static final double[][] SQUARE = {
            {37.50, 127.00}, {37.52, 127.00}, {37.52, 127.03}, {37.50, 127.03}, {37.50, 127.00}};
    private static final double[] WEST = {37.51, 126.98};
    private static final double[] EAST = {37.51, 127.05};
    private static final double WIDTH_KM = 0.03 * 111.320 * Math.cos(Math.toRadians(37.51));

    private static WaterBodies water(String name, String kind) {
        WaterBodies w = new WaterBodies("없는파일.geojson");
        w.add(name, kind, List.<double[][]>of(SQUARE));
        return w;
    }

    @Test
    @DisplayName("가로지르면 물의 폭만큼 잰다")
    void 가로지른_길이() {
        var spans = water("가강", WaterBodies.RIVER).onWater(List.of(WEST, EAST));

        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).name()).isEqualTo("가강");
        assertThat(spans.get(0).kind()).isEqualTo(WaterBodies.RIVER);
        assertThat(spans.get(0).km()).isCloseTo(WIDTH_KM, within(0.02));
    }

    /** 지도에 물 위 토막을 파랗게 덧그린다 — 토막의 양 끝은 물가다 */
    @Test
    @DisplayName("물 위에 있는 토막의 양 끝을 준다")
    void 물_위_토막() {
        var span = water("가강", WaterBodies.RIVER).onWater(List.of(WEST, EAST)).get(0);

        assertThat(span.pieces()).hasSize(1);
        double[][] piece = span.pieces().get(0);
        assertThat(piece[0][0]).isCloseTo(37.51, within(1e-9));
        assertThat(piece[0][1]).isCloseTo(127.00, within(1e-6));     // 서쪽 물가
        assertThat(piece[1][1]).isCloseTo(127.03, within(1e-6));     // 동쪽 물가
    }

    @Test
    @DisplayName("강은 우회 판정에 들어가지 않는다 — 건너야 하는 물이다")
    void 강은_우회하지_않는다() {
        WaterBodies w = water("가강", WaterBodies.RIVER);
        assertThat(w.crossing(WEST, EAST)).isEmpty();
        assertThat(water("가저수지", WaterBodies.LAKE).crossing(WEST, EAST)).hasSize(1);
    }

    @Test
    @DisplayName("한쪽 끝이 물 안이면 물가까지만 잰다")
    void 끝이_물_안() {
        double[] inside = {37.51, 127.015};       // 서쪽 물가에서 0.015° 들어간 곳
        var spans = water("가강", WaterBodies.RIVER).onWater(List.of(WEST, inside));
        assertThat(spans.get(0).km()).isCloseTo(WIDTH_KM / 2, within(0.02));
    }

    @Test
    @DisplayName("같은 물을 여러 구간이 지나면 이름별로 더한다")
    void 이름별로_더한다() {
        // 서→동으로 건넜다가 북쪽으로 빠져 다시 서쪽으로 돌아오며 한 번 더 건넌다
        double[] eastNorth = {37.515, 127.05};
        double[] westNorth = {37.515, 126.98};
        var spans = water("가강", WaterBodies.RIVER).onWater(List.of(WEST, EAST, eastNorth, westNorth));
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).km()).isCloseTo(WIDTH_KM * 2, within(0.04));
        assertThat(spans.get(0).pieces()).hasSize(2);
    }

    @Test
    @DisplayName("물을 안 지나면 아무것도 없다")
    void 안_지나면_없다() {
        var spans = water("가강", WaterBodies.RIVER)
                .onWater(List.of(new double[]{37.55, 126.98}, new double[]{37.55, 127.05}));
        assertThat(spans).isEmpty();
    }
}
