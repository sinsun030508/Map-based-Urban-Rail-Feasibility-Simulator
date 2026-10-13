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
 * 비교표에 붙는 "기존 수단" 한 줄.
 *
 * 고르는 규칙이 둘이라 **어느 쪽을 낼지**가 조용히 뒤집히기 쉽다. 도시철도 판정이 먼저
 * 걸렸는데 KTX 를 내면 "강남~잠실에 KTX 를 타라" 가 되고, 반대로 KTX 판정을 아예 안
 * 보면 서울~부산에 324km 트램만 늘어놓는다.
 *
 * `label` 도 고정한다 — 비교표 수단 칸은 가장 긴 이름에 맞춰 폭이 잡히므로
 * (`table-layout: auto`) 여기에 노선 목록이 들어가면 다른 수단 이름이 대신 접힌다.
 */
class ExistingOptionsTest {

    private static final double URBAN_MAX_KM = 35.0;    // 지하철 권장 연장
    private static final double LAT0 = 37.5000;
    private static final double LNG0 = 127.0000;

    private static final double[] INCHEON = {37.456, 126.705};
    private static final double[] SEJONG = {36.480, 127.289};

    /**
     * 위도만 0.002°(약 222m)씩 올라가는 가상의 도시철도. `ExistingLineFinder` 는 양 끝이
     * 역 1km 안이고 노선의 80% 가 역세권이어야 걸리므로 선 위에 역을 깔아 준다.
     */
    private static Path stations(Path dir, String lineName, int count) throws IOException {
        StringBuilder sb = new StringBuilder(
                "station_code,station_name,line_name,latitude,longitude\n");
        for (int i = 0; i < count; i++) {
            sb.append("c,").append(lineName).append(i).append(',').append(lineName)
                    .append(',').append(LAT0 + i * 0.002).append(',').append(LNG0).append('\n');
        }
        Path file = dir.resolve("station_master.csv");
        Files.writeString(file, sb, StandardCharsets.UTF_8);
        return file;
    }

    private static double[] onLadder(int index) {
        return new double[]{LAT0 + index * 0.002, LNG0};
    }

    private static ExistingLineFinder urban(Path file) {
        ExistingLineFinder finder = new ExistingLineFinder(
                file == null ? "없는파일.csv" : file.toString(), "없는지방파일.csv");
        finder.load();
        return finder;
    }

    /** 광명·오송이 경부선·호남선에 함께 선다 — 실제 `ktx_station.csv` 좌표다 */
    private static IntercityRailFinder ktx() {
        IntercityRailFinder finder = new IntercityRailFinder("없는파일.csv");
        for (String line : List.of("KTX 경부선", "KTX 호남선")) {
            finder.add(line, "광명", 37.416360, 126.884719);
            finder.add(line, "오송", 36.620240, 127.327286);
        }
        return finder;
    }

    @Test
    @DisplayName("도시 안 노선은 기존 도시철도를 낸다")
    void 도시철도(@TempDir Path dir) throws IOException {
        // 역 21개 = 약 4.4km 짜리 노선
        ExistingOptions options = new ExistingOptions(urban(stations(dir, "2호선", 21)), ktx());

        var option = options.find(List.of(onLadder(0), onLadder(20)), 4.4, URBAN_MAX_KM);

        assertThat(option).isNotNull();
        assertThat(option.kind()).isEqualTo(ExistingOptions.URBAN_RAIL);
        assertThat(option.label()).isEqualTo("기존 2호선");
        assertThat(option.detail()).contains("100%");
        assertThat(option.message()).contains("2호선").contains("활용 검토");
    }

    @Test
    @DisplayName("도시 간 거리는 기존 KTX 를 낸다")
    void 도시간_KTX() {
        ExistingOptions options = new ExistingOptions(urban(null), ktx());

        var option = options.find(List.of(INCHEON, SEJONG), 120.3, URBAN_MAX_KM);

        assertThat(option).isNotNull();
        assertThat(option.kind()).isEqualTo(ExistingOptions.INTERCITY_RAIL);
        // 수단 칸에는 짧은 이름만 — 노선 목록은 설명으로 내린다
        assertThat(option.label()).isEqualTo("기존 KTX");
        assertThat(option.detail())
                .contains("KTX 경부선·호남선")
                .contains("광명역(출발에서 ")
                .contains("오송역(도착에서 ");
    }

    /**
     * **둘 다 걸리면 도시철도가 이긴다.** 도시철도 판정(양 끝이 역 1km 안 + 노선의 80% 가
     * 역세권)이 KTX 판정(양 끝에서 20km 안)보다 훨씬 촘촘해 더 강한 근거다. 순서가 뒤집히면
     * 이미 전철이 다니는 길에 "KTX 를 타라" 고 안내한다.
     */
    @Test
    @DisplayName("둘 다 걸리면 도시철도가 우선이다")
    void 우선순위(@TempDir Path dir) throws IOException {
        // 역 164개 = 약 36.2km — 지하철 권장 연장(35km)을 넘어 KTX 판정도 함께 열린다
        ExistingLineFinder withStations = urban(stations(dir, "1호선", 164));
        double[] from = onLadder(0);
        double[] to = onLadder(163);

        // 먼저 KTX 쪽도 실제로 걸리는 상황임을 확인한다 — 안 걸리면 이 테스트는
        // 우선순위를 시험하지 않고 통과한다 (IntercityRailFinderTest 에서 겪은 함정)
        IntercityRailFinder intercity = new IntercityRailFinder("없는파일.csv");
        intercity.add("KTX 가상선", "남쪽", from[0], from[1]);
        intercity.add("KTX 가상선", "북쪽", to[0], to[1]);
        assertThat(intercity.find(List.of(from, to), 36.2, URBAN_MAX_KM)).isNotNull();

        var option = new ExistingOptions(withStations, intercity)
                .find(List.of(from, to), 36.2, URBAN_MAX_KM);

        assertThat(option.kind()).isEqualTo(ExistingOptions.URBAN_RAIL);
        assertThat(option.label()).isEqualTo("기존 1호선");
    }

    @Test
    @DisplayName("어느 쪽도 안 걸리면 아무 줄도 붙이지 않는다")
    void 해당_없음() {
        ExistingOptions options = new ExistingOptions(urban(null), ktx());

        // 짧은 도시 안 노선 — 도시철도 자료가 없고 KTX 판정은 길이에서 막힌다
        assertThat(options.find(List.of(onLadder(0), onLadder(20)), 4.4, URBAN_MAX_KM)).isNull();
    }

    @Test
    @DisplayName("기준 길이는 관리자가 고치는 max_length_km 를 따른다")
    void 기준_길이() {
        // max_length_km 가 없는 수단(복선전철·급행)은 세지 않는다 — 그러면 모든 노선이
        // '권장 연장 안' 이 되어 KTX 안내가 영구히 꺼진다
        assertThat(ExistingOptions.urbanMaxKm(java.util.Map.of())).isEqualTo(Double.MAX_VALUE);
    }
}
