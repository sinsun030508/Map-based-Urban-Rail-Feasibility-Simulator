package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.reference.CostStandard;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 비용 구성 — 합계만 저장돼 있어 그 결과가 쓴 단가로 되짚는다. 연장 몫을 나머지로 구하므로 세 몫의 합은
 * 늘 합계다(역이 비켜 서 늘어난 연장도 연장 몫에 든다). 되짚을 수 없으면 「모름」 이지 0 이 아니다.
 */
class CostPartsTest {

    private static CostStandard standard(long fixed, long perKm, long perStation) {
        try {
            Constructor<CostStandard> c = CostStandard.class.getDeclaredConstructor();
            c.setAccessible(true);
            CostStandard s = c.newInstance();
            ReflectionTestUtils.setField(s, "fixedCost", fixed);
            ReflectionTestUtils.setField(s, "costPerKm", perKm);
            ReflectionTestUtils.setField(s, "costPerStation", perStation);
            return s;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("세 몫으로 나누고 합은 합계와 같다 — 10km·역 5개: 1,000 + 5,000 + 1,000")
    void 나눈다() {
        var parts = ScenarioDto.CostParts.of(7_000, 5, standard(1_000, 500, 200), null);

        assertThat(parts.fixed()).isEqualTo(1_000);
        assertThat(parts.stations()).isEqualTo(1_000);
        assertThat(parts.length()).isEqualTo(5_000);
        assertThat(parts.fixed() + parts.length() + parts.stations()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("단가 행이 없으면 모른다")
    void 단가가_없으면_모른다() {
        assertThat(ScenarioDto.CostParts.of(7_000, 5, null, null)).isNull();
    }

    @Test
    @DisplayName("단가가 바뀐 옛 결과라 나머지가 음수면 모른다 — 음수 막대를 그리지 않는다")
    void 나머지가_음수면_모른다() {
        assertThat(ScenarioDto.CostParts.of(1_000, 5, standard(1_000, 500, 200), null)).isNull();
    }

    @Test
    @DisplayName("결과가 쓴 단가가 내려갔으면 낡은 결과다 — 단가에는 시각 칸이 없어 시각으로는 못 본다")
    void 내려간_단가를_쓴_결과() {
        CostStandard active = standard(1, 1, 1);
        ReflectionTestUtils.setField(active, "active", true);
        CostStandard old = standard(1, 1, 1);
        ReflectionTestUtils.setField(old, "active", false);
        Map<Long, CostStandard> byId = Map.of(1L, active, 2L, old);

        assertThat(ScenarioDto.standardChanged(List.of(1L), byId)).isFalse();
        assertThat(ScenarioDto.standardChanged(List.of(1L, 2L), byId)).isTrue();
    }

    @Test
    @DisplayName("단가 행을 모르면 낡았다고 단정하지 않는다")
    void 모르는_단가() {
        assertThat(ScenarioDto.standardChanged(java.util.Arrays.asList(9L, null), Map.of())).isFalse();
    }

    @Test
    @DisplayName("산 구간 터널비는 연장 몫에 섞지 않고 따로 뗀다 — 네 몫의 합이 합계다")
    void 터널비를_뗀다() {
        var parts = ScenarioDto.CostParts.of(7_600, 5, standard(1_000, 500, 200), 600L);

        assertThat(parts.tunnel()).isEqualTo(600);
        assertThat(parts.length()).isEqualTo(5_000);
        assertThat(parts.fixed() + parts.length() + parts.stations() + parts.tunnel()).isEqualTo(7_600);
        // 모르는 터널비(NULL)는 합계에 들어 있지 않다 — 0 몫이다
        assertThat(ScenarioDto.CostParts.of(7_000, 5, standard(1_000, 500, 200), null).tunnel()).isZero();
    }
}
