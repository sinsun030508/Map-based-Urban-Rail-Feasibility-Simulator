package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalDouble;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 뚫는 안 — 그린 선 그대로 갈 때 35‰ 를 지키려면 어디를 터널로 파야 하나.
 *
 * <p>남북으로 곧은 10km 노선에 가짜 지형을 얹는다. 위도 0.001° ≈ 0.111km 라 `s` km 를 위도로 바꿔
 * 높이를 준다. 선로 높이는 「대칭 기울기 완화」 로 구한다(ledger Task 2 Ruling) — 산은 깎고 골짜기는 돋운다.
 */
class MountainProfileTest {

    private static final double LAT0 = 36.0, LNG = 128.0, KM_PER_DEG = 111.195;
    private static final List<double[]> ROUTE =
            List.of(new double[]{LAT0, LNG}, new double[]{LAT0 + 10 / KM_PER_DEG, LNG});

    /** 출발에서 s km 인 곳의 높이를 주는 가짜 지형 */
    private static Elevation terrain(DoubleUnaryOperator heightAtKm) {
        return (lat, lng) -> OptionalDouble.of(heightAtKm.applyAsDouble((lat - LAT0) * KM_PER_DEG));
    }

    @Test
    @DisplayName("평지면 구간이 없다")
    void flat() {
        assertThat(MountainProfile.sections(ROUTE, terrain(s -> 50))).isEmpty();
    }

    @Test
    @DisplayName("35‰ 보다 완만한 언덕(30‰)은 구간이 없다")
    void gentleHill() {
        assertThat(MountainProfile.sections(ROUTE, terrain(s -> 30 * s))).isEmpty();
    }

    @Test
    @DisplayName("원뿔 산은 터널이 된다 — 높이 400m · 밑지름 4km")
    void cone() {
        var sections = MountainProfile.sections(ROUTE, terrain(s -> Math.max(0, 400 * (1 - Math.abs(s - 5) / 2))));

        assertThat(sections).hasSize(1);
        var m = sections.get(0);
        assertThat(m.tunnelKm()).isBetween(1.0, 4.0);
        assertThat(m.maxCoverM()).isGreaterThan(100);
        assertThat(m.startKm()).isLessThan(5).isGreaterThan(2);
        assertThat(m.endKm()).isGreaterThan(5).isLessThan(8);
    }

    @Test
    @DisplayName("골짜기는 터널이 아니다 — 골짜기 옆 고원 밑에 가짜 터널을 파지 않는다")
    void valley() {
        // 높이 300m 고원 가운데에 깊이 300m 골짜기. 하한 포락선이면 고원 밑을 수백 m 파고든다(Ruling)
        assertThat(MountainProfile.sections(ROUTE, terrain(s -> Math.abs(s - 5) < 0.5 ? 0 : 300))).isEmpty();
    }

    @Test
    @DisplayName("바다는 터널이 아니다 — 수심은 0 으로 잘린다")
    void sea() {
        // Terrain 이 음수를 0 으로 자른다. 여기서는 잘린 모양(해안 10m · 바다 0m)을 준다
        assertThat(MountainProfile.sections(ROUTE, terrain(s -> s > 3 && s < 7 ? 0 : 10))).isEmpty();
    }

    @Test
    @DisplayName("높이를 모르는 표본이 낀 산은 판정하지 않는다 — 「산 없음」 이 아니라 「모름」 이다")
    void unknownMiddle() {
        Elevation e = (lat, lng) -> {
            double s = (lat - LAT0) * KM_PER_DEG;
            return Math.abs(s - 5) < 0.5 ? OptionalDouble.empty()
                    : OptionalDouble.of(Math.max(0, 400 * (1 - Math.abs(s - 5) / 2)));
        };
        assertThat(MountainProfile.sections(ROUTE, e)).isEmpty();
    }

    @Test
    @DisplayName("가까운 터널 토막은 하나로 합친다 — 봉우리 사이 홈이 선로 높이(약 90m)까지 내려와 0.3km 끊긴다. 터널 길이에는 틈을 넣지 않는다")
    void notchedRidgeMerges() {
        var sections = MountainProfile.sections(ROUTE, terrain(s ->
                Math.abs(s - 5) < 0.15 ? 60 : Math.max(0, 400 * (1 - Math.abs(s - 5) / 2.5))));

        assertThat(sections).hasSize(1);
        var m = sections.get(0);
        assertThat(m.tunnelKm()).isLessThan(m.endKm() - m.startKm());
    }

    @Test
    @DisplayName("멀리 떨어진 두 산은 따로 센다")
    void twoConesStaySeparate() {
        var sections = MountainProfile.sections(ROUTE, terrain(s ->
                Math.max(0, 400 * (1 - Math.min(Math.abs(s - 2.5), Math.abs(s - 7.5)) / 2))));

        assertThat(sections).hasSize(2);
    }

    @Test
    @DisplayName("건물 숲처럼 좁게 튄 높이는 산이 아니다 — 지형 자료(SRTM)는 건물 꼭대기까지 잰다")
    void buildingSpike() {
        // 역삼역 둘레가 이랬다: 45 → 71 → 80 → 64 → 47m (폭 0.2km)
        assertThat(MountainProfile.sections(ROUTE, terrain(s -> Math.abs(s - 5) < 0.05 ? 80 : 40))).isEmpty();
    }

    @Test
    @DisplayName("도로는 더 가파르게 오를 수 있다 — 50‰ 언덕은 철도(35‰)엔 터널, 도로(7%)엔 아니다")
    void roadClimbsSteeper() {
        Elevation hill = terrain(s -> Math.max(0, 150 - 50 * Math.abs(s - 5)));
        assertThat(MountainProfile.sections(ROUTE, hill)).isNotEmpty();
        assertThat(MountainProfile.sections(ROUTE, hill, MountainProfile.ROAD_GRADE)).isEmpty();
        // 100‰ 원뿔(높이 200m)은 둘 다 터널이지만 도로가 짧다 — 200‰ 처럼 너무 가파르면 둘 다 같아진다
        Elevation cone = terrain(s -> Math.max(0, 200 * (1 - Math.abs(s - 5) / 2)));
        double rail = MountainProfile.sections(ROUTE, cone).stream().mapToDouble(MountainProfile.Section::tunnelKm).sum();
        double road = MountainProfile.sections(ROUTE, cone, MountainProfile.ROAD_GRADE).stream()
                .mapToDouble(MountainProfile.Section::tunnelKm).sum();
        assertThat(road).isGreaterThan(0).isLessThan(rail);
    }
}
