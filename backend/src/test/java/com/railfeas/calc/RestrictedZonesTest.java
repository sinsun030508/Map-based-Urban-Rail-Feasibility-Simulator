package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.geo.Haversine;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 확인 필요 구역 — 선분이 원을 지나는가, 피해 가는 선형(11-12 사용자 요청) */
class RestrictedZonesTest {

    private static final RestrictedZones.Zone GYEONGBOK =
            new RestrictedZones.Zone("경복궁 일대", "REVIEW", "역사문화환경 보존지역", 37.5790, 126.9770, 1.0);
    private static final RestrictedZones.Zone CHANGDEOK =
            new RestrictedZones.Zone("창덕궁·종묘 일대", "REVIEW", "역사문화환경 보존지역", 37.5790, 126.9910, 1.0);
    private static final RestrictedZones.Zone PAJU =
            new RestrictedZones.Zone("파주 민통선 이북", "BLOCKED", "군사기지법", 37.8880, 126.7420, 5.0);

    /** 사용자 화면(11-12) — 서대문구청 쪽에서 한성대입구 쪽으로 점 둘. 양 끝은 두 궁에서 2.8km 넘게 떨어져 있다 */
    private static final List<double[]> SEODAEMUN_HANSUNG =
            List.of(new double[]{37.5736461, 126.9372454}, new double[]{37.5892438, 127.0073508});

    @Test
    @DisplayName("양 끝이 밖이어도 선이 원을 지나면 지난다 — 노선을 따라 닿는 순서로")
    void 선이_지나면_지난다() {
        assertThat(RestrictedZones.crossed(SEODAEMUN_HANSUNG, List.of(CHANGDEOK, PAJU, GYEONGBOK)))
                .extracting(RestrictedZones.Zone::name).containsExactly("경복궁 일대", "창덕궁·종묘 일대");
    }

    @Test
    @DisplayName("반경 밖으로 비껴가면 지나지 않는다")
    void 비껴가면_아니다() {
        // 경복궁 중심에서 남쪽 1.3km 를 동서로
        List<double[]> south = List.of(new double[]{37.5673, 126.9500}, new double[]{37.5673, 127.0000});
        assertThat(RestrictedZones.crossed(south, List.of(GYEONGBOK))).isEmpty();
    }

    @Test
    @DisplayName("피해 가는 선형 — 경유점이 모두 원 밖이고, 1.5배 안이다")
    void 피해_간다() {
        // 경복궁 한가운데를 동서로 지나는 4km
        List<double[]> through = List.of(new double[]{37.5790, 126.9545}, new double[]{37.5790, 126.9995});
        WaterDetour.Plan plan = RestrictedZones.detour(List.of(GYEONGBOK)).plan(through);

        assertThat(plan.crossings()).hasSize(1);
        assertThat(plan.crossings().get(0).name()).isEqualTo("경복궁 일대");
        assertThat(plan.crossings().get(0).detoured()).isTrue();
        assertThat(plan.points().size()).isGreaterThan(2);
        for (double[] p : plan.points()) {
            assertThat(Haversine.distanceKm(p[0], p[1], GYEONGBOK.lat(), GYEONGBOK.lng())).isGreaterThan(1.0);
        }
        assertThat(RestrictedZones.crossed(plan.points(), List.of(GYEONGBOK))).isEmpty();
    }

    @Test
    @DisplayName("못 피하면 이유를 구역 말로 적는다 — 「물」 이 아니다")
    void 이유는_구역_말로() {
        // 시작점이 원 한가운데 — 피할 수 없다
        List<double[]> fromInside = List.of(new double[]{37.5790, 126.9770}, new double[]{37.5790, 127.0200});
        WaterDetour.Plan plan = RestrictedZones.detour(List.of(GYEONGBOK)).plan(fromInside);

        assertThat(plan.crossings()).hasSize(1);
        assertThat(plan.crossings().get(0).detoured()).isFalse();
        assertThat(plan.crossings().get(0).reason()).contains("구역").doesNotContain("물");
    }
}
