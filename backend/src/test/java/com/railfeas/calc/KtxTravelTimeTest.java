package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 구간 소요시간 조회. 값은 `etl/korail_travel_time.py` 가 받아 둔 것이고
 * 여기서는 **읽고 고르는 규칙**만 가린다.
 *
 * <p>숫자는 2026-10-20 서울~부산 실측이다 — 고속선 44편(중앙 164분, 143~176),
 * 구포 경유 11편(중앙 198분, 180~205).
 */
class KtxTravelTimeTest {

    private KtxTravelTime times;

    @BeforeEach
    void setUp() {
        times = new KtxTravelTime("없는파일.csv");
        times.add("서울", "부산", 1, 44, 164, 143, 176);
        times.add("서울", "부산", 2, 11, 198, 180, 205);
        times.add("광명", "오송", 1, 12, 41, 38, 45);
    }

    @Test
    void 빠른_계통의_중앙값을_돌려준다() {
        assertThat(times.fastestMedian("서울", "부산")).isEqualTo(164);
    }

    @Test
    void 방향을_가리지_않는다() {
        // 대표역을 어느 쪽에서 골랐느냐에 따라 순서가 뒤집힌다. 같은 구간이다
        assertThat(times.fastestMedian("부산", "서울")).isEqualTo(164);
    }

    @Test
    void 모르는_구간은_null_이지_0_이_아니다() {
        // 0 으로 돌려주면 비교표에 "0분" 이 찍혀 KTX 가 순간이동하는 것처럼 보인다
        assertThat(times.fastestMedian("서울", "강릉")).isNull();
    }

    @Test
    void 계통을_rank_순서로_돌려준다() {
        var legs = times.legs("서울", "부산");
        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).medianMin()).isEqualTo(164);
        assertThat(legs.get(1).medianMin()).isEqualTo(198);
    }

    @Test
    void 계통이_하나뿐인_구간도_읽는다() {
        assertThat(times.legs("광명", "오송")).hasSize(1);
        assertThat(times.fastestMedian("오송", "광명")).isEqualTo(41);
    }

    @Test
    void 자료가_없어도_기동을_막지_않는다() {
        // 파일이 없으면 이 안내만 빠진다 — ExistingLineFinder 와 같은 약속이다
        var empty = new KtxTravelTime("없는파일.csv");
        assertThat(empty.legs("서울", "부산")).isEmpty();
        assertThat(empty.fastestMedian("서울", "부산")).isNull();
    }
}
