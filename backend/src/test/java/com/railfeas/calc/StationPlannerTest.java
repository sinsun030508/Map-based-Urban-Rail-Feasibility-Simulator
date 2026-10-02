package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.geo.Haversine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 정차역 배치의 약속을 고정한다.
 *
 * 배치는 "인구 많은 쪽으로 당기되 역간격은 지킨다"는 두 요구가 맞서는 일이라,
 * 한쪽을 손보면 다른 쪽이 조용히 깨진다. 그 경계를 테스트로 박아 둔다.
 */
class StationPlannerTest {

    /** 서울시청에서 남쪽으로 쭉 내려가는 직선. 위도만 바뀌어 거리 계산이 단순하다 */
    private static final double LAT0 = 37.5665;
    private static final double LNG0 = 126.9780;
    private static final List<double[]> ROUTE =
            List.of(new double[]{LAT0, LNG0}, new double[]{LAT0 - 0.09, LNG0});

    private StationPlanner planner(Path censusFile) {
        PopulationIndex index = new PopulationIndex(
                censusFile == null ? "없는파일.csv" : censusFile.toString(), "없는파일.csv");
        index.load();
        return new StationPlanner(index);
    }

    /** 노선 중간 한 곳에만 사람이 몰려 있는 자료 */
    private Path censusWithPeakAt(Path dir, double lat) throws IOException {
        Path file = dir.resolve("census.csv");
        StringBuilder sb = new StringBuilder("adm_cd,latitude,longitude,population,dong_name\n");
        // 노선 전체에 얇게 깔고
        for (int i = 0; i <= 90; i++) {
            sb.append("x,").append(LAT0 - i * 0.001).append(',').append(LNG0)
                    .append(",10,배경동\n");
        }
        // 한 지점에만 두텁게 얹는다
        sb.append("peak,").append(lat).append(',').append(LNG0).append(",50000,인구봉우리동\n");
        Files.writeString(file, sb, StandardCharsets.UTF_8);
        return file;
    }

    private double lengthKm() {
        return Haversine.distanceKm(LAT0, LNG0, LAT0 - 0.09, LNG0);
    }

    @Test
    @DisplayName("종점은 사용자가 찍은 점에 고정된다")
    void terminalsStayWhereTheUserPutThem() {
        List<StationPlanner.Placed> placed = planner(null).plan(ROUTE, 8, 1.2);

        assertThat(placed).hasSize(8);
        assertThat(placed.get(0).lat()).isEqualTo(LAT0);
        assertThat(placed.get(7).lat()).isEqualTo(LAT0 - 0.09);
    }

    @Test
    @DisplayName("인구 자료가 없으면 균등 분할 그대로 둔다")
    void withoutPopulationItStaysEvenlySpaced() {
        List<StationPlanner.Placed> placed = planner(null).plan(ROUTE, 6, 1.2);

        double expected = lengthKm() / 5;
        for (int i = 1; i < placed.size(); i++) {
            double gap = Haversine.distanceKm(
                    placed.get(i - 1).lat(), placed.get(i - 1).lng(),
                    placed.get(i).lat(), placed.get(i).lng());
            assertThat(gap).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.01));
        }
        assertThat(placed).allSatisfy(p -> assertThat(p.score()).isZero());
    }

    @Test
    @DisplayName("이상 위치에서 못 닿는 인구 봉우리까지 끌려간다")
    void stationsMoveTowardPopulation(@TempDir Path dir) throws IOException {
        // 봉우리를 이상 위치에서 0.7km 떨어뜨린다 — 역세권 반경(0.5km) 밖이라
        // 가만히 있으면 못 담고, 창(±0.42km) 안이라 움직이면 담을 수 있다.
        // 점수가 계단 함수라 봉우리가 이미 반경에 들어와 있으면 역은 움직이지 않는다
        double idealLat = LAT0 - 0.09 / 5;
        double peakLat = idealLat - 0.7 / 111.0;
        StationPlanner withPeak = planner(censusWithPeakAt(dir, peakLat));

        List<StationPlanner.Placed> plain = planner(null).plan(ROUTE, 6, 1.2);
        List<StationPlanner.Placed> pulled = withPeak.plan(ROUTE, 6, 1.2);

        assertThat(Math.abs(pulled.get(1).lat() - peakLat))
                .isLessThan(Math.abs(plain.get(1).lat() - peakLat));
        assertThat(pulled.get(1).name()).isEqualTo("인구봉우리동");
        assertThat(pulled.get(1).score()).isGreaterThan(50_000);
    }

    @Test
    @DisplayName("인구로 끌려가도 창 밖으로는 못 나간다")
    void pullIsBoundedByTheWindow(@TempDir Path dir) throws IOException {
        // 끝 쪽에 아주 큰 봉우리를 둬도 중간 역이 거기까지 쏠리면 안 된다
        StationPlanner withPeak = planner(censusWithPeakAt(dir, LAT0 - 0.085));
        double spacing = 1.2;
        List<StationPlanner.Placed> placed = withPeak.plan(ROUTE, 6, spacing);

        double length = lengthKm();
        for (int i = 1; i < placed.size() - 1; i++) {
            double ideal = length * i / 5;
            double at = Haversine.distanceKm(LAT0, LNG0, placed.get(i).lat(), placed.get(i).lng());
            // 창은 역간격의 ±35% — 경계에서 한 걸음(0.1km) 의 여유만 둔다
            assertThat(Math.abs(at - ideal)).isLessThanOrEqualTo(spacing * 0.35 + 0.1);
        }
    }

    @Test
    @DisplayName("역 순서는 뒤집히지 않고 최소 간격을 지킨다")
    void stationsKeepOrderAndMinimumGap(@TempDir Path dir) throws IOException {
        StationPlanner withPeak = planner(censusWithPeakAt(dir, LAT0 - 0.02));
        double spacing = 1.2;
        List<StationPlanner.Placed> placed = withPeak.plan(ROUTE, 9, spacing);

        for (int i = 1; i < placed.size(); i++) {
            double gap = Haversine.distanceKm(
                    placed.get(i - 1).lat(), placed.get(i - 1).lng(),
                    placed.get(i).lat(), placed.get(i).lng());
            assertThat(gap).as("역 %d-%d 간격", i - 1, i).isGreaterThan(0);
            // 종점은 고정이라 마지막 구간은 짧아질 수 있다 — 중간 구간만 본다
            if (i < placed.size() - 1) {
                assertThat(gap).isGreaterThanOrEqualTo(spacing * 0.5 - 0.05);
            }
            assertThat(placed.get(i).lat()).isLessThan(placed.get(i - 1).lat());
        }
    }

    @Test
    @DisplayName("같은 동에 역이 둘이면 이름에 번호를 붙인다")
    void duplicateNamesGetNumbered(@TempDir Path dir) throws IOException {
        // 배경동이 노선 전체에 깔려 있어 중간 역들이 모두 같은 이름을 받는다
        List<StationPlanner.Placed> placed =
                planner(censusWithPeakAt(dir, LAT0 - 0.02)).plan(ROUTE, 6, 1.2);

        List<String> names = placed.stream().map(StationPlanner.Placed::name).toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).anyMatch(n -> n != null && n.endsWith(" 2"));
    }

    @Test
    @DisplayName("역이 2개 미만이면 배치하지 않는다")
    void needsAtLeastTwoStations() {
        assertThat(planner(null).plan(ROUTE, 1, 1.2)).isEmpty();
        assertThat(planner(null).plan(List.of(new double[]{LAT0, LNG0}), 5, 1.2)).isEmpty();
    }
}
