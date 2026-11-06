package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.reference.CostStandard;
import java.lang.reflect.Constructor;
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
        var parts = ScenarioDto.CostParts.of(7_000, 5, standard(1_000, 500, 200));

        assertThat(parts.fixed()).isEqualTo(1_000);
        assertThat(parts.stations()).isEqualTo(1_000);
        assertThat(parts.length()).isEqualTo(5_000);
        assertThat(parts.fixed() + parts.length() + parts.stations()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("단가 행이 없으면 모른다")
    void 단가가_없으면_모른다() {
        assertThat(ScenarioDto.CostParts.of(7_000, 5, null)).isNull();
    }

    @Test
    @DisplayName("단가가 바뀐 옛 결과라 나머지가 음수면 모른다 — 음수 막대를 그리지 않는다")
    void 나머지가_음수면_모른다() {
        assertThat(ScenarioDto.CostParts.of(1_000, 5, standard(1_000, 500, 200))).isNull();
    }
}
