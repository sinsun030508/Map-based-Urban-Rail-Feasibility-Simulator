package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
 * 해안선 — 점이 바다인지, 선이 바다 위를 몇 km 지나는지(11-18). **서쪽 바다로 쏜 선이 해안선과 만나는 횟수의 짝홀**로 가른다 —
 * 가까운 선분의 방향(육지가 왼쪽)으로 가르면 오목한 만에서 틀리고, DEM 으로는 바다와 간척지가 둘 다 0m 라 안 갈린다.
 */
class CoastlineTest {

    /** 닫힌 섬 하나 — 위도 37.0~37.2, 경도 126.0~126.2 */
    private static final double[][] ISLAND = {{37.00, 126.00}, {37.00, 126.20}, {37.20, 126.20}, {37.20, 126.00}, {37.00, 126.00}};

    /** 동쪽으로 입을 벌린 만이 있는 섬(C 자) — 만은 위도 37.15~37.25, 경도 126.2~126.4. 바운딩 박스 안이어도 만 안은 바다다 */
    private static final double[][] C_ISLAND = {
            {37.00, 126.00}, {37.00, 126.40}, {37.15, 126.40}, {37.15, 126.20}, {37.25, 126.20},
            {37.25, 126.40}, {37.40, 126.40}, {37.40, 126.00}, {37.00, 126.00}};

    /** `List.of(이차원 배열)` 은 배열을 「원소 여러 개」 로 읽는다 — 가변 인자로 받아 감싼다 */
    private static Coastline coast(double[][]... ways) {
        return Coastline.of(java.util.Arrays.asList(ways));
    }

    /** 마름모 섬 — 서쪽 꼭짓점(37.1, 126.0)이 섬 안의 점과 같은 위도다. 꼭짓점을 두 번 세면 육지가 바다로 뒤집히는 모양이다 */
    private static final double[][] DIAMOND = {{37.00, 126.10}, {37.10, 126.20}, {37.20, 126.10}, {37.10, 126.00}, {37.00, 126.10}};

    private static double[] p(double lat, double lng) {
        return new double[]{lat, lng};
    }

    @Test
    @DisplayName("섬 안은 육지, 섬 밖 서쪽과 동쪽은 바다 — 동쪽은 섬을 두 번 가로질러도 짝수라 바다다")
    void insideIsLandOutsideIsSea() {
        Coastline c = coast(ISLAND);
        assertThat(c.isSea(37.10, 126.10)).isFalse();
        assertThat(c.isSea(37.10, 125.90)).as("섬 서쪽 바다").isTrue();
        assertThat(c.isSea(37.10, 126.30)).as("섬 동쪽 바다 — 서쪽으로 쏘면 섬을 두 번 지난다").isTrue();
    }

    @Test
    @DisplayName("오목한 만 안은 바다다 — 가까운 선분의 방향으로 가르면 틀리는 자리")
    void concaveBayIsSea() {
        Coastline c = coast(C_ISLAND);
        assertThat(c.isSea(37.20, 126.30)).as("만 안 — 바운딩 박스 안이다").isTrue();
        assertThat(c.isSea(37.10, 126.10)).as("남쪽 팔").isFalse();
        assertThat(c.isSea(37.30, 126.30)).as("북쪽 팔").isFalse();
    }

    @Test
    @DisplayName("해안선 꼭짓점과 같은 위도의 점도 맞다 — 반개구간으로 세어 꼭짓점을 두 번 세지 않는다")
    void sameLatitudeAsAVertex() {
        Coastline c = coast(ISLAND);
        assertThat(c.isSea(37.20, 126.30)).as("섬 위쪽 꼭짓점과 같은 위도, 동쪽 바다").isTrue();
        assertThat(c.isSea(37.00, 126.30)).as("섬 아래쪽 꼭짓점과 같은 위도, 동쪽 바다").isTrue();
        assertThat(c.isSea(37.20, 125.90)).as("서쪽 바다").isTrue();
    }

    @Test
    @DisplayName("꼭짓점을 지나는 선은 한 번만 센다 — 마름모 안의 점이 서쪽 꼭짓점과 같은 위도여도 육지다")
    void passingThroughAVertexCountsOnce() {
        Coastline c = coast(DIAMOND);
        assertThat(c.isSea(37.10, 126.10)).as("마름모 한가운데 — 닫힌 구간으로 세면 서쪽 꼭짓점이 두 번 세어져 바다가 된다").isFalse();
        assertThat(c.isSea(37.10, 126.30)).as("마름모 동쪽 바다").isTrue();
        assertThat(c.isSea(37.10, 125.90)).as("마름모 서쪽 바다").isTrue();
    }

    @Test
    @DisplayName("섬을 가로지르는 선은 양쪽 바다 두 토막만 센다 — 섬 위는 땅이다")
    void seaPiecesAcrossTheIsland() {
        Coastline c = coast(ISLAND);
        List<double[][]> pieces = c.seaPieces(p(37.10, 125.90), p(37.10, 126.30));
        assertThat(pieces).hasSize(2);
        double lngKm = com.railfeas.geo.Haversine.distanceKm(37.10, 126.0, 37.10, 126.1);   // 0.1° 의 길이
        assertThat(c.seaKm(List.of(p(37.10, 125.90), p(37.10, 126.30)))).isCloseTo(2 * lngKm, within(0.05));
    }

    @Test
    @DisplayName("바다는 물 구간 하나로 — 이름 「바다」, 종류 sea. 섬 양쪽 두 토막이 한 구간에 든다")
    void seaSpanIsOneSpanNamedSea() {
        Coastline c = coast(ISLAND);
        var span = c.seaSpan(List.of(p(37.10, 125.90), p(37.10, 126.30)), null).orElseThrow();
        assertThat(span.name()).isEqualTo("바다");
        assertThat(span.kind()).isEqualTo(Coastline.SEA);
        assertThat(span.pieces()).hasSize(2);
        double lngKm = com.railfeas.geo.Haversine.distanceKm(37.10, 126.0, 37.10, 126.1);
        assertThat(span.km()).isCloseTo(2 * lngKm, within(0.05));
    }

    @Test
    @DisplayName("이미 호수·강으로 센 토막은 바다에서 뺀다 — 강 하구에서 물 파일과 바다가 겹쳐도 한 번만 센다")
    void seaSpanSkipsPiecesAlreadyCountedAsWater() {
        Coastline c = coast(ISLAND);
        List<double[]> route = List.of(p(37.10, 125.90), p(37.10, 126.30));
        double lngKm = com.railfeas.geo.Haversine.distanceKm(37.10, 126.0, 37.10, 126.1);
        // 서쪽 토막(경도 < 126.1)만 강으로 센다고 하면 동쪽 토막만 남는다
        var eastOnly = c.seaSpan(route, (lat, lng) -> lng < 126.1).orElseThrow();
        assertThat(eastOnly.km()).isCloseTo(lngKm, within(0.05));
        assertThat(c.seaSpan(route, (lat, lng) -> true)).as("전부 강으로 셌으면 바다는 비어 있다").isEmpty();
    }

    @Test
    @DisplayName("바다를 안 지나면 구간이 비어 있다 — 0km 짜리 구간을 만들지 않는다")
    void noSeaMeansNoSpan() {
        Coastline c = coast(ISLAND);
        assertThat(c.seaSpan(List.of(p(37.05, 126.05), p(37.15, 126.15)), null)).isEmpty();
    }

    @Test
    @DisplayName("땅 위만 지나는 선은 0 — 섬 안쪽")
    void landOnlyRouteIsZero() {
        Coastline c = coast(ISLAND);
        assertThat(c.seaKm(List.of(p(37.05, 126.05), p(37.15, 126.15)))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("점이 둘 이상인 선은 구간마다 더한다")
    void sumsOverSegments() {
        Coastline c = coast(ISLAND);
        double one = c.seaKm(List.of(p(37.10, 125.90), p(37.10, 126.00)));
        double both = c.seaKm(List.of(p(37.10, 125.90), p(37.10, 126.00), p(37.10, 125.90)));
        assertThat(one).isGreaterThan(0.0);
        assertThat(both).isCloseTo(2 * one, within(0.01));
    }

    @Test
    @DisplayName("자료 범위 밖(북위 38.7° 위)은 바다로 세지 않는다 — 거기는 자료가 없어 횟수가 0 이라 바다처럼 보인다")
    void outsideCoverageIsNotSea() {
        Coastline c = coast(ISLAND);
        assertThat(c.isSea(39.50, 127.00)).isFalse();
        assertThat(c.isSea(32.50, 127.00)).isFalse();
        assertThat(c.seaKm(List.of(p(39.50, 127.00), p(39.60, 127.10)))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("해안선이 비어 있으면 꺼진다 — 육지로 읽지도 바다로 읽지도 않는다")
    void emptyIsUnavailable() {
        Coastline c = Coastline.of(List.of());
        assertThat(c.available()).isFalse();
        assertThat(c.seaKm(List.of(p(37.10, 125.90), p(37.10, 126.30)))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("gz 파일에서 읽어도 같다 — 한 줄에 way 하나, 위도,경도 쌍을 공백으로")
    void readsTheGzFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("coastline.csv.gz");
        StringBuilder line = new StringBuilder();
        for (double[] q : ISLAND) {
            line.append(String.format("%.5f,%.5f ", q[0], q[1]));
        }
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(f))) {
            out.write((line.toString().trim() + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        Coastline c = new Coastline(f.toString());
        c.load();
        assertThat(c.available()).isTrue();
        assertThat(c.isSea(37.10, 126.10)).isFalse();
        assertThat(c.isSea(37.10, 125.90)).isTrue();
    }

    @Test
    @DisplayName("파일이 없으면 꺼진다 — 「바다 없음」 이 아니다")
    void missingFileIsUnavailable(@TempDir Path dir) {
        Coastline c = new Coastline(dir.resolve("없음.csv.gz").toString());
        c.load();
        assertThat(c.available()).isFalse();
        assertThat(c.seaKm(List.of(p(37.10, 125.90), p(37.10, 126.30)))).isEqualTo(0.0);
    }
}
