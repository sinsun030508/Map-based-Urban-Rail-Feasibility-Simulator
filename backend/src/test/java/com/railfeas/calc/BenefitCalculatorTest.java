package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 편익·현재가치 계산 고정 — 투자평가지침 제7판을 코드로 옮긴 부분이라 수식이 바뀌면
 * 바로 드러나야 한다. 기대값은 지침 조문에서 손으로 계산해 적었다.
 */
class BenefitCalculatorTest {

    private static final double DISCOUNT_SUM = 14.8529052903;   // 아래 설명 참고

    private BenefitCalculator calculator() {
        return new BenefitCalculator(null);
    }

    private BenefitCalculator.Params params() {
        Map<String, Double> v = new HashMap<>();
        v.put("baseline_speed_kmh", 20.0);
        v.put("time_value_won_per_hour", 10_000.0);
        v.put("analysis_years", 40.0);
        v.put("construction_years", 5.0);
        v.put("discount_rate", 0.045);
        v.put("discount_rate_late", 0.035);
        v.put("discount_switch_year", 30.0);
        v.put("peak_hour_ratio", 0.1);
        v.put("peak_direction_ratio", 0.3);
        return new BenefitCalculator.Params(v);
    }

    @Test
    @DisplayName("통행시간 절감편익은 지침대로 2단계 할인한 현재가치다")
    void benefitIsDiscountedPresentValue() {
        // 10km 를 20km/h → 30km/h 로 줄이면 통행당 1/6시간이 절감된다.
        // 연 편익 = 1/6 × 10,000명 × 365일 × 10,000원 = 60.83억
        // 할인계수 합 = Σ(y=1..40) 1 / (1.045^(5+min(y,30)) × 1.035^max(0,y-30)) = 14.8529
        //   · 공사 5년이 지나야 편익이 시작된다 (지침 6.2.2 (4))
        //   · 개통 후 30년까지 4.5%, 31~40년은 3.5% (지침 6.2.1, 6.3.1 ②)
        // → 60.83억 × 14.8529 = 904억
        Long benefit = calculator().benefitTotal(params(), 10_000, 10.0, 30.0);

        assertThat(benefit).isEqualTo(904L);
        double yearly = (10.0 / 20 - 10.0 / 30) * 10_000 * 365 * 10_000;
        assertThat(benefit).isEqualTo(Math.round(yearly * DISCOUNT_SUM / 100_000_000.0));
    }

    @Test
    @DisplayName("기준 속도보다 느린 수단은 편익이 0이다")
    void slowerThanBaselineEarnsNothing() {
        // BRT 저급형(18km/h)이 시내버스(21.3km/h)보다 느려 0 이 되는 상황이다.
        // 오류가 아니라 "시간을 절감하지 못한다"는 결과다 — 음의 편익은 세지 않는다
        assertThat(calculator().benefitTotal(params(), 10_000, 10.0, 20.0)).isZero();
        assertThat(calculator().benefitTotal(params(), 10_000, 10.0, 15.0)).isZero();
    }

    @Test
    @DisplayName("수요를 못 구하면 편익도 없다 — 0 이 아니라 미산출이다")
    void unknownDemandGivesNull() {
        assertThat(calculator().benefitTotal(params(), null, 10.0, 30.0)).isNull();
        assertThat(calculator().bcRatio(null, 1000)).isNull();
    }

    @Test
    @DisplayName("사업비 현재가치는 표 6-1 의 연차별 투입비율을 따른다")
    void costPresentValueFollowsGuideline() {
        // 공사 5년에 10·20·30·30·10% 투입, 4.5% 할인 → 명목의 0.8735배
        assertThat(calculator().costPresentValue(params(), 10_000)).isEqualTo(8_735L);
        assertThat(calculator().costPresentValue(params(), 0)).isZero();
    }

    @Test
    @DisplayName("B/C 는 편익과 비용을 모두 현재가치로 나눈 값이다")
    void bcRatioUsesPresentValues() {
        assertThat(calculator().bcRatio(8_735L, 10_000))
                .isEqualByComparingTo(BigDecimal.valueOf(0.874));
        // 비용이 0 이면 나눌 수 없다 — 무한대 대신 미산출로 둔다
        assertThat(calculator().bcRatio(100L, 0)).isNull();
    }

    @Test
    @DisplayName("첨두 단면은 일 이용객 × 첨두율 × 환산계수다")
    void peakIsConvertedFromDailyRiders() {
        // 역 승하차 합계를 최대 단면 방향 통행량으로 바꾸는 계수(혼잡도 실측)
        assertThat(calculator().peakPphpd(params(), 100_000)).isEqualTo(3_000);
        assertThat(calculator().peakPphpd(params(), null)).isNull();
    }

    @Test
    @DisplayName("없는 원단위를 쓰면 조용히 넘어가지 않고 멈춘다")
    void missingParameterFailsLoudly() {
        BenefitCalculator.Params empty = new BenefitCalculator.Params(new HashMap<>());
        assertThat(empty.hasDemandModel()).isFalse();
        try {
            empty.get("time_value_won_per_hour");
            assertThat(false).as("예외가 나야 한다").isTrue();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("time_value_won_per_hour");
        }
    }
}
