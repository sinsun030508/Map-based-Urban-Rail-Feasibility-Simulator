package com.railfeas.reference;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.calc.CalculationRules;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 저장된 결과가 낡았는지 가르는 시각. **기준값(DB)만 보면 계산 규칙이 바뀐 것을 모른다** —
 * 편익의 전환 전 수단에 기존 KTX 를 넣은 뒤에도 전에 저장한 서울~부산이 아무 표시 없이
 * B/C 3.86 을 보여 줬다. 규칙이 바뀐 시각도 함께 본다.
 */
class ReferenceControllerTest {

    private static ReferenceController controller(LocalDateTime params, LocalDateTime modes) {
        BenefitParameterRepository p = Mockito.mock(BenefitParameterRepository.class);
        ModeCapacityRepository m = Mockito.mock(ModeCapacityRepository.class);
        Mockito.when(p.lastUpdatedAt()).thenReturn(params);
        Mockito.when(m.lastUpdatedAt()).thenReturn(modes);
        return new ReferenceController(m, null, null, p);
    }

    @Test
    @DisplayName("계산 규칙이 기준값보다 나중에 바뀌었으면 규칙의 시각을 낸다")
    void 규칙이_나중이면_규칙() {
        LocalDateTime older = CalculationRules.CHANGED_AT.minusDays(3);
        assertThat(controller(older, older.minusDays(1)).referenceUpdatedAt())
                .isEqualTo(CalculationRules.CHANGED_AT);
    }

    @Test
    @DisplayName("관리자가 기준값을 그 뒤에 고쳤으면 기준값의 시각을 낸다")
    void 기준값이_나중이면_기준값() {
        LocalDateTime later = CalculationRules.CHANGED_AT.plusHours(1);
        assertThat(controller(null, later).referenceUpdatedAt()).isEqualTo(later);
        assertThat(controller(later, null).referenceUpdatedAt()).isEqualTo(later);
    }

    /**
     * 규칙 시각을 **미래로** 적으면 그 시각까지 새로 계산한 결과에도 "다시 계산" 알림이 뜬다.
     * 시각을 올릴 때 "배포할 즈음" 을 어림해 두 번이나 미래로 적었다 — 고친 시각(지금)을 적는다.
     */
    @Test
    @DisplayName("계산 규칙 시각은 미래가 아니다")
    void 규칙_시각은_미래가_아니다() {
        assertThat(CalculationRules.CHANGED_AT).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    @DisplayName("기준값 시각이 비어 있어도 규칙의 시각은 낸다")
    void 비어도_규칙은_있다() {
        assertThat(controller(null, null).referenceUpdatedAt())
                .isEqualTo(CalculationRules.CHANGED_AT);
    }
}
