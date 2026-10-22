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
    private static ExistingOptions options(ExistingLineFinder urban,
                                           IntercityRailFinder intercity, KtxTravelTime times) {
        return options(urban, intercity, times, new UrbanLineSpeed("없는파일.csv"));
    }

    private static ExistingOptions options(ExistingLineFinder urban, IntercityRailFinder intercity,
                                           KtxTravelTime times, UrbanLineSpeed speeds) {
        return new ExistingOptions(urban, intercity, times, speeds);
    }

    /** 2호선 공표 표정속도 — 실제 `rail_speed.csv` 값이다 */
    private static UrbanLineSpeed line2() {
        UrbanLineSpeed speeds = new UrbanLineSpeed("없는파일.csv");
        speeds.add("서울 2호선", 32.5);
        return speeds;
    }

    /** 소요시간 자료가 없는 상태. 대부분의 테스트는 그 칸을 보지 않는다 */
    private static KtxTravelTime empty() {
        return new KtxTravelTime("없는파일.csv");
    }

    
    /**
     * 실제로 받은 2026-10-20 서울~부산 값. 역 이름만 이 테스트의 좌표 고정물
     * (광명·오송)에 맞춰 놓았다 — 보는 것은 계통이 둘일 때의 표기다.
     */
    private static KtxTravelTime seoulBusan() {
        KtxTravelTime t = new KtxTravelTime("없는파일.csv");
        t.add("광명", "오송", 1, 44, 164, 143, 176);
        t.add("광명", "오송", 2, 11, 198, 180, 205);
        return t;
    }

    private static IntercityRailFinder ktx() {
        IntercityRailFinder finder = new IntercityRailFinder("없는파일.csv");
        for (String line : List.of("KTX 경부선", "KTX 호남선")) {
            finder.add(line, "광명", 37.416360, 126.884719, true);
            finder.add(line, "오송", 36.620240, 127.327286, true);
        }
        return finder;
    }

    @Test
    @DisplayName("도시 안 노선은 기존 도시철도를 낸다")
    void 도시철도(@TempDir Path dir) throws IOException {
        // 역 21개 = 약 4.4km 짜리 노선
        ExistingOptions options = options(urban(stations(dir, "2호선", 21)), ktx(), empty());

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
        ExistingOptions options = options(urban(null), ktx(), empty());

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
        intercity.add("KTX 가상선", "남쪽", from[0], from[1], false);
        intercity.add("KTX 가상선", "북쪽", to[0], to[1], false);
        assertThat(intercity.find(List.of(from, to), 36.2, URBAN_MAX_KM)).isNotNull();

        var option = options(withStations, intercity, empty())
                .find(List.of(from, to), 36.2, URBAN_MAX_KM);

        assertThat(option.kind()).isEqualTo(ExistingOptions.URBAN_RAIL);
        assertThat(option.label()).isEqualTo("기존 1호선");
    }

    @Test
    @DisplayName("어느 쪽도 안 걸리면 아무 줄도 붙이지 않는다")
    void 해당_없음() {
        ExistingOptions options = options(urban(null), ktx(), empty());

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

    @Test
    @DisplayName("소요시간은 빠른 계통의 중앙값이고, 갈리는 폭은 따로 적는다")
    void 소요시간을_채운다() {
        var option = options(urban(null), ktx(), seoulBusan())
                .find(List.of(INCHEON, SEJONG), 120.3, URBAN_MAX_KM);

        assertThat(option.kind()).isEqualTo(ExistingOptions.INTERCITY_RAIL);
        assertThat(option.travelMin()).isEqualTo(164);
        // 역이 수십 km 마다라 평균 통행거리(13.6km)짜리 이동에는 못 탄다
        assertThat(option.servesShortTrips()).isFalse();
        // 좁은 칸에는 한 값만 들어가므로 "그게 전부가 아니다" 를 설명 줄이 말해 준다
        assertThat(option.travelDetail())
                .isEqualTo("44편 143~176분(중앙 164분) · 경로가 다른 11편 180~205분(중앙 198분)");
    }

    /**
     * 편익의 전환 전 시간으로 쓰는 값. 표의 164분은 **역에서 역까지**라 그대로 쓰면 KTX 가
     * 신설안보다 유리해진다 — 신설안은 그린 점에서 그린 점까지이기 때문이다.
     */
    @Test
    @DisplayName("문앞~문앞 시간은 역까지 이동과 빠른 계통 중앙값의 합이다")
    void 문앞에서_문앞까지() {
        var option = options(urban(null), ktx(), seoulBusan())
                .find(List.of(INCHEON, SEJONG), 120.3, URBAN_MAX_KM);
        var match = ktx().find(List.of(INCHEON, SEJONG), 120.3, URBAN_MAX_KM);

        assertThat(option.accessKm()).isEqualTo(match.fromKm() + match.toKm());
        assertThat(option.doorToDoorHours(20.0))
                .isCloseTo(option.accessKm() / 20.0 + 164 / 60.0,
                        org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("소요시간을 모르면 문앞~문앞 시간도 모른다 — 편익 기준을 바꾸지 않는다")
    void 모르면_기준을_안_바꾼다(@TempDir Path dir) throws IOException {
        var ktxUnknown = options(urban(null), ktx(), empty())
                .find(List.of(INCHEON, SEJONG), 120.3, URBAN_MAX_KM);
        assertThat(ktxUnknown.doorToDoorHours(20.0)).isNull();

        // 공표 표정속도를 모르는 도시철도도 마찬가지다 (9호선·코레일 광역선 등)
        var urbanOption = options(urban(stations(dir, "2호선", 21)), ktx(), empty())
                .find(List.of(onLadder(0), onLadder(20)), 4.4, URBAN_MAX_KM);
        assertThat(urbanOption.doorToDoorHours(20.0)).isNull();
    }

    /**
     * 2호선을 따라 지하철을 그리면 2호선을 타던 사람은 시내버스가 아니라 2호선에서 갈아탄다.
     * 그런데 편익은 시내버스 대비로 셌다 — 이미 있는 노선과 같은 속도의 신설안이 시간을
     * 아끼는 것처럼 나왔다.
     */
    @Test
    @DisplayName("도시철도는 공표 표정속도로 그린 길이를 환산한다")
    void 도시철도_소요시간(@TempDir Path dir) throws IOException {
        var option = options(urban(stations(dir, "2호선", 21)), ktx(), empty(), line2())
                .find(List.of(onLadder(0), onLadder(20)), 4.4, URBAN_MAX_KM);

        assertThat(option.kind()).isEqualTo(ExistingOptions.URBAN_RAIL);
        assertThat(option.travelMin()).isEqualTo(8);                 // 4.4 ÷ 32.5 × 60 = 8.1
        assertThat(option.servesShortTrips()).isTrue();             // 역이 1km 마다다
        assertThat(option.travelDetail()).isEqualTo("공표 표정속도 32.5km/h 로 그린 길이를 환산한 값");
        // 양 끝이 역 위에 있으니 역까지 갈 거리가 없다
        assertThat(option.accessKm()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
        // 편익에는 반올림한 8분이 아니라 정확한 시간을 쓴다 — 아래 테스트 참고
        assertThat(option.doorToDoorHours(20.0)).isCloseTo(4.4 / 32.5,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    /**
     * 표의 칸은 정수 분이지만 편익은 정확한 시간으로 센다. 반올림한 분을 쓰면 **기존 노선과
     * 속도가 같은 신설안**이 시간을 아끼는 것처럼 나온다 — 신분당선(47.6)을 따라 복선전철
     * (47.6)을 10km 그리면 둘 다 12.6분인데, 기존 쪽이 13분으로 올림되면 신설이 0.4분 빠르다.
     */
    @Test
    @DisplayName("속도가 같은 신설안은 아끼는 시간이 없다 — 반올림하지 않는다")
    void 같은_속도면_절감이_없다(@TempDir Path dir) throws IOException {
        UrbanLineSpeed speeds = new UrbanLineSpeed("없는파일.csv");
        speeds.add("서울 2호선", 47.6);
        // 역 46개 = 약 10km
        var option = options(urban(stations(dir, "2호선", 46)), ktx(), empty(), speeds)
                .find(List.of(onLadder(0), onLadder(45)), 10.0, URBAN_MAX_KM);

        assertThat(option.travelMin()).isEqualTo(13);              // 12.6 → 표에는 13분
        BenefitCalculator benefits = new BenefitCalculator(null);
        Long saved = benefits.benefitTotal(BenefitCalculatorTest.params(), 10_000, 10.0, 47.6,
                option.doorToDoorHours(20.0), option.servesShortTrips());
        assertThat(saved).isZero();
    }

    @Test
    @DisplayName("소요시간 자료가 없으면 0 이 아니라 빈 칸이다")
    void 모르면_비운다() {
        var option = options(urban(null), ktx(), empty())
                .find(List.of(INCHEON, SEJONG), 120.3, URBAN_MAX_KM);

        // 0 으로 채우면 비교표에 "0분" 이 찍혀 KTX 가 순간이동하는 것처럼 보인다
        assertThat(option.travelMin()).isNull();
        assertThat(option.travelDetail()).isNull();
    }

    @Test
    @DisplayName("설명 줄의 단위는 표와 같다 — 분이다")
    void 설명_줄은_분으로_적는다() {
        // 칸이 `164분` 인데 이 줄이 `2시간 23분~2시간 56분` 이면 둘을 잇지 못한다.
        // 표의 신설안 행도 모두 분이라, 비교하려면 같은 단위여야 한다
        var one = ExistingOptions.spread(List.of(new KtxTravelTime.Leg(1, 44, 164, 143, 176)));
        assertThat(one).isEqualTo("44편 143~176분(중앙 164분)");
    }

    @Test
    @DisplayName("계통이 둘이면 둘 다 적는다 — 칸의 한 숫자가 어느 쪽인지 보이게")
    void 계통이_둘이면_둘_다_적는다() {
        var two = ExistingOptions.spread(List.of(
                new KtxTravelTime.Leg(1, 44, 164, 143, 176),
                new KtxTravelTime.Leg(2, 11, 198, 180, 205)));
        assertThat(two).isEqualTo(
                "44편 143~176분(중앙 164분) · 경로가 다른 11편 180~205분(중앙 198분)");
    }

    @Test
    @DisplayName("계통이 없으면 설명 줄도 없다 — 빈 문자열이 아니라 null")
    void 모르면_설명도_없다() {
        // 빈 문자열이면 화면이 "— ." 처럼 찍는다. 화면이 분기할 수 있게 null 로 준다
        assertThat(ExistingOptions.spread(List.of())).isNull();
    }
}
