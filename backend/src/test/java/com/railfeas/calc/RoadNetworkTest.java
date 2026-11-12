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

/** 큰길로 붙이기 — 가장 가까운 「선분 위」 점이고(꼭짓점이 아니다), 0.3km 밖은 옮기지 않는다 */
class RoadNetworkTest {

    /** 노량진 둘레 동서 큰길 하나 — 꼭짓점은 양 끝(1.8km 떨어져)뿐이다 */
    private static final double[][] EAST_WEST = {{37.5100, 126.9300}, {37.5100, 126.9500}};

    @Test
    @DisplayName("가장 가까운 큰길의 선분 위 점으로 옮긴다 — 꼭짓점이 멀어도")
    void 선분_위로_옮긴다() {
        RoadNetwork r = RoadNetwork.of(List.<double[][]>of(EAST_WEST));
        double[] at = r.snap(new double[]{37.5110, 126.9400});   // 길 북쪽 약 110m

        assertThat(at[0]).isCloseTo(37.5100, within(1e-6));
        assertThat(at[1]).isCloseTo(126.9400, within(1e-6));
    }

    @Test
    @DisplayName("0.3km 밖의 큰길로는 옮기지 않는다 — 자리가 그대로다")
    void 먼_길은_그대로() {
        RoadNetwork r = RoadNetwork.of(List.<double[][]>of(EAST_WEST));
        double[] far = {37.5140, 126.9400};                       // 약 440m

        assertThat(r.snap(far)).isSameAs(far);
        assertThat(r.nearest(37.5124, 126.9400, RoadNetwork.MAX_SNAP_KM)).isPresent();   // 약 265m 는 옮긴다
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
    @DisplayName("파일을 읽는다(gzip, 한 줄에 토막 하나) — 없으면 꺼지고 자리를 그대로 둔다")
    void 파일(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("roads.csv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(f))) {
            out.write("37.51000,126.93000 37.51000,126.95000\n".getBytes(StandardCharsets.US_ASCII));
        }
        RoadNetwork r = new RoadNetwork(f.toString());
        r.load();
        assertThat(r.available()).isTrue();
        assertThat(r.snap(new double[]{37.5110, 126.9400})[0]).isCloseTo(37.5100, within(1e-5));

        RoadNetwork none = new RoadNetwork(dir.resolve("없는파일.csv.gz").toString());
        none.load();
        double[] p = {37.5110, 126.9400};
        assertThat(none.available()).isFalse();
        assertThat(none.snap(p)).isSameAs(p);
    }
}
