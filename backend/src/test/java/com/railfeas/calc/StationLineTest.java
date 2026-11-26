package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지도가 그리는 철도 노선(`frontend/src/geo/routeLine.js` 의 `routeThroughStations`)과 **같은 선**을 서버가 만든다(11-26).
 * 터널·구역을 그 선으로 재야 지도의 갈색·파란 토막과 비교표 건설비가 한 선을 말한다 — 직선으로 재면 역이 큰길로 옮겨진 노선에서 토막이 허공에 떴다.
 * 경우는 JS 테스트(`routeLine.test.js`)와 같게 둔다. 점은 [위도, 경도]
 */
class StationLineTest {

    private static StationPlanner.Placed at(double lat, double lng) {
        return new StationPlanner.Placed(lat, lng, "역", 0);
    }

    @Test
    @DisplayName("종점역을 큰길로 옮겼으면 선이 그 역에서 시작해 그 역에서 끝난다 — 찍은 점으로 꺾여 들어가지 않는다")
    void startsAndEndsAtTerminalStations() {
        List<double[]> points = List.of(new double[]{37.50, 127.00}, new double[]{37.60, 127.00});
        var line = StationLine.through(points, List.of(at(37.50, 127.003), at(37.55, 127.004), at(37.60, 127.003)));

        assertThat(line).containsExactly(new double[]{37.50, 127.003}, new double[]{37.55, 127.004}, new double[]{37.60, 127.003});
    }

    @Test
    @DisplayName("그린 꺾임(경유점)은 선 위 거리 순으로 역과 엮어 남긴다 — 역만 이으면 꺾임이 사라진다")
    void keepsDrawnBendsInOrder() {
        // ㄱ자: 북으로 10km, 동으로 10km. 역은 양 끝과 동쪽 다리 한가운데뿐(역간격이 넓은 수단)
        List<double[]> points = List.of(new double[]{37.50, 127.00}, new double[]{37.59, 127.00}, new double[]{37.59, 127.11});
        var line = StationLine.through(points, List.of(at(37.50, 127.00), at(37.59, 127.055), at(37.59, 127.11)));

        assertThat(line).containsExactly(new double[]{37.50, 127.00}, new double[]{37.59, 127.00},
                new double[]{37.59, 127.055}, new double[]{37.59, 127.11});
    }

    @Test
    @DisplayName("역이 찍은 점 그대로면 길이 0 조각 없이 그 점들이다")
    void dropsDuplicatePoints() {
        List<double[]> points = List.of(new double[]{37.50, 127.00}, new double[]{37.60, 127.00});
        var line = StationLine.through(points, List.of(at(37.50, 127.00), at(37.60, 127.00)));

        assertThat(line).containsExactly(new double[]{37.50, 127.00}, new double[]{37.60, 127.00});
    }

    @Test
    @DisplayName("역이 둘 미만이면 그린 선 그대로")
    void fewerThanTwoStationsKeepsTheDrawnLine() {
        List<double[]> points = List.of(new double[]{37.50, 127.00}, new double[]{37.60, 127.00});

        assertThat(StationLine.through(points, List.of(at(37.55, 127.0)))).isSameAs(points);
        assertThat(StationLine.through(points, List.of())).isSameAs(points);
    }
}
