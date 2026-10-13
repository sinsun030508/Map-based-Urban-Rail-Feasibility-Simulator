package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 기존 KTX 안내. 너무 잘 걸리면 도시 안 노선에도 "KTX 를 타라" 고 하고,
 * 너무 안 걸리면 인천~세종에 지하철 역 60개를 그려 놓고 아무 말도 안 한다.
 * 좌표는 data/build/ktx_station.csv 의 실제 값이다.
 */
class IntercityRailFinderTest {

    private static final double URBAN_MAX_KM = 35.0;    // 지하철 권장 연장
    private static final double[] INCHEON = {37.456, 126.705};
    private static final double[] SEJONG = {36.480, 127.289};

    private IntercityRailFinder finder;

    @BeforeEach
    void setUp() {
        finder = new IntercityRailFinder("없는파일.csv");
        for (String line : List.of("KTX 경부선", "KTX 호남선")) {
            finder.add(line, "서울", 37.554695, 126.970702, false);   // 선로 계통은 경부선(재래선)
            finder.add(line, "광명", 37.416360, 126.884719, true);
            finder.add(line, "오송", 36.620240, 127.327286, true);
        }
        finder.add("KTX 경부선", "대전", 36.332108, 127.434194, true);
        finder.add("KTX 강릉선", "서울", 37.554695, 126.970702, false);
        finder.add("KTX 강릉선", "강릉", 37.764500, 128.899500, false);
    }

    @Test
    void 인천에서_세종은_광명_오송_KTX를_안내한다() {
        var match = finder.find(List.of(INCHEON, SEJONG), 120.3, URBAN_MAX_KM);

        assertThat(match).isNotNull();
        assertThat(match.fromStation()).isEqualTo("광명");
        assertThat(match.toStation()).isEqualTo("오송");
        // 두 역을 함께 서는 노선만 적는다 — 강릉선은 오송에 안 선다
        assertThat(match.lines()).containsExactly("KTX 경부선", "호남선");
        assertThat(match.message()).startsWith("기존 KTX 경부선·호남선 활용 검토 — 광명역(출발에서 ");
    }

    @Test
    void 도시철도_권장_연장_안이면_묻지_않는다() {
        assertThat(finder.find(List.of(INCHEON, SEJONG), URBAN_MAX_KM, URBAN_MAX_KM)).isNull();
    }

    @Test
    void 역이_접근_거리_밖이면_안내하지_않는다() {
        double[] taean = {36.745, 126.298};                  // 가장 가까운 KTX 역이 50km 넘게 떨어져 있다
        assertThat(finder.find(List.of(INCHEON, taean), 90, URBAN_MAX_KM)).isNull();
    }

    @Test
    void 두_역_사이가_노선에_비해_짧으면_같은_축이_아니다() {
        // 양 끝이 각각 광명·서울에서 18km(접근 거리 안)지만 두 역은 17km 거리다. 그린 노선은
        // 53km — 출발·도착 근처에 역이 있다는 것만으로 "KTX 가 이 구간을 잇는다" 고 할 수 없다.
        // **처음 고른 좌표는 광명에서 20.8km 라 접근 거리에서 먼저 걸러졌다** — 이 조건을
        // 시험하지 않고 통과하고 있었다
        double[] west = {37.300, 126.740};
        double[] east = {37.670, 127.115};
        assertThat(finder.find(List.of(west, east), 60, URBAN_MAX_KM)).isNull();
    }

    /**
     * 거의 같은 거리면 고속선 역을 고른다.
     *
     * 부산시청에서 구포 7.67km(경부선) / 부산 7.73km(경부고속)이라 **60m** 차이로 구포가
     * 뽑히고 있었다. 틀린 답은 아니지만 좌표가 OSM 에서 오므로 편집 한 번에 뒤집힌다.
     */
    @Test
    void 거의_같은_거리면_고속선_역을_고른다() {
        IntercityRailFinder f = new IntercityRailFinder("없는파일.csv");
        f.add("KTX 경부선", "서울", 37.554695, 126.970702, false);
        f.add("KTX 경부선", "구포", 35.205308, 128.997301, false);
        f.add("KTX 경부선", "부산", 35.115553, 129.042587, true);

        double[] seoulCityHall = {37.5665, 126.9780};
        double[] busanCityHall = {35.1796, 129.0756};
        var match = f.find(List.of(seoulCityHall, busanCityHall), 340.5, URBAN_MAX_KM);

        assertThat(match).isNotNull();
        assertThat(match.toStation()).isEqualTo("부산");
        // 출발 쪽은 서울역(재래선)이 그대로다 — 고속선이 여유 안에 없다
        assertThat(match.fromStation()).isEqualTo("서울");
    }

    /**
     * **고속선 우선이 "가까움" 을 이기면 안 된다.** 서울역은 선로 계통이 경부선(재래선)이고
     * 광명역이 경부고속이라, 무조건 고속선을 고르면 서울시청에서 1.5km 떨어진 서울역을
     * 두고 17km 떨어진 광명역을 안내한다 — 고치기 전보다 나쁘다.
     */
    @Test
    void 고속선이라도_훨씬_멀면_고르지_않는다() {
        double[] seoulCityHall = {37.5665, 126.9780};
        var match = finder.find(List.of(seoulCityHall, SEJONG), 120.3, URBAN_MAX_KM);

        assertThat(match).isNotNull();
        assertThat(match.fromStation()).isEqualTo("서울");     // 광명(경부고속)이 아니다
        assertThat(match.fromKm()).isLessThan(IntercityRailFinder.HIGH_SPEED_SLACK_KM);
    }

    @Test
    void 파일이_없으면_조용히_비활성이다() {
        IntercityRailFinder empty = new IntercityRailFinder("없는파일.csv");
        empty.load();
        assertThat(empty.find(List.of(INCHEON, SEJONG), 120, URBAN_MAX_KM)).isNull();
    }
}
