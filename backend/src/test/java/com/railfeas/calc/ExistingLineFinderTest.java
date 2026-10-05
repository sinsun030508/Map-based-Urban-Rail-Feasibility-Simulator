package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 기존 노선 활용 안내의 경계를 고정한다.
 *
 * 너무 잘 걸리면 신설이 타당한 노선까지 "이미 있다"고 말하고, 너무 안 걸리면 이미 전철이
 * 다니는 길에 또 짓는 안을 1위로 올린다. 양 끝 근접과 겹침 비율 **둘 다** 봐야 하는 이유다.
 */
class ExistingLineFinderTest {

    private static final double LAT0 = 37.5000;
    private static final double LNG0 = 127.0000;

    /** 위도만 0.002°(약 222m)씩 올라가는 가상의 노선 */
    private Path stations(Path dir, String... lines) throws IOException {
        StringBuilder sb = new StringBuilder("station_code,station_name,line_name,latitude,longitude\n");
        for (String line : lines) {
            String[] parts = line.split(":");          // 이름:시작오프셋:개수
            double offset = Double.parseDouble(parts[1]);
            int count = Integer.parseInt(parts[2]);
            for (int i = 0; i < count; i++) {
                sb.append("c,").append(parts[0]).append(i).append(',').append(parts[0])
                        .append(',').append(LAT0 + offset + i * 0.002)
                        .append(',').append(LNG0).append('\n');
            }
        }
        Path file = dir.resolve("station_master.csv");
        Files.writeString(file, sb, StandardCharsets.UTF_8);
        return file;
    }

    private ExistingLineFinder finder(Path file) {
        ExistingLineFinder f = new ExistingLineFinder(
                file == null ? "없는파일.csv" : file.toString());
        f.load();
        return f;
    }

    /** 기존 노선을 그대로 따라 그린 노선 */
    private List<double[]> alongLine() {
        return List.of(new double[]{LAT0, LNG0}, new double[]{LAT0 + 0.038, LNG0});
    }

    @Test
    @DisplayName("기존 노선을 그대로 덧그리면 활용을 권한다")
    void overlappingRouteIsFlagged(@TempDir Path dir) throws IOException {
        var match = finder(stations(dir, "2호선:0:20")).find(alongLine());

        assertThat(match).isNotNull();
        assertThat(match.lineName()).isEqualTo("2호선");
        assertThat(match.coverage()).isGreaterThan(0.9);
        assertThat(match.message()).contains("2호선").contains("활용 검토");
    }

    @Test
    @DisplayName("양 끝만 붙고 경로가 다르면 걸리지 않는다")
    void sameEndsButDifferentPathIsNotFlagged(@TempDir Path dir) throws IOException {
        // 역은 양 끝에만 있고 가운데가 비었다 — 전혀 다른 축으로 돌아가는 노선이다
        var match = finder(stations(dir, "우회선:0:2", "우회선:0.036:2")).find(alongLine());

        assertThat(match).isNull();
    }

    @Test
    @DisplayName("한쪽 끝이 멀면 걸리지 않는다")
    void routeStartingFarFromTheLineIsNotFlagged(@TempDir Path dir) throws IOException {
        var finder = finder(stations(dir, "2호선:0:20"));
        // 출발점을 서쪽으로 5km 옮긴다 (경도 0.06°)
        var away = List.of(new double[]{LAT0, LNG0 - 0.06}, new double[]{LAT0 + 0.038, LNG0});

        assertThat(finder.find(away)).isNull();
    }

    @Test
    @DisplayName("더 많이 겹치는 노선을 고른다")
    void picksTheLineThatOverlapsMost(@TempDir Path dir) throws IOException {
        // 두 노선이 같은 축에 있고 하나는 중간이 비어 있다
        var match = finder(stations(dir, "촘촘선:0:20", "듬성선:0:6")).find(alongLine());

        assertThat(match).isNotNull();
        assertThat(match.lineName()).isEqualTo("촘촘선");
    }

    @Test
    @DisplayName("역 좌표가 없으면 조용히 비활성화된다 — 계산을 막지 않는다")
    void withoutStationFileItStaysQuiet() {
        assertThat(finder(null).find(alongLine())).isNull();
    }

    @Test
    @DisplayName("점이 하나뿐이면 판정하지 않는다")
    void singlePointIsNotARoute(@TempDir Path dir) throws IOException {
        var finder = finder(stations(dir, "2호선:0:20"));

        assertThat(finder.find(List.of(new double[]{LAT0, LNG0}))).isNull();
    }
}
