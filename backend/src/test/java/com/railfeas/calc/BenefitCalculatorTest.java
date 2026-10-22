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

    static BenefitCalculator.Params params() {
        Map<String, Double> v = new HashMap<>();
        v.put("baseline_speed_kmh", 20.0);
        v.put("time_value_won_per_hour", 10_000.0);
        v.put("analysis_years", 40.0);
        v.put("construction_years", 5.0);
        v.put("discount_rate", 0.045);
        v.put("discount_rate_late", 0.035);
        v.put("discount_switch_year", 30.0);
        v.put("weekday_factor", 1.1);
        v.put("peak_hour_ratio", 0.1);
        v.put("peak_direction_coef", 9.0);     // K = 9/연장, 상한 0.3
        v.put("peak_direction_max", 0.3);
        v.put("avg_trip_km", 13.6);            // 서울시 2019 지하철 1회 이동
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
    @DisplayName("기존 수단이 시내버스보다 빠르면 그 시간에서 절감을 센다")
    void existingFasterOptionCapsBaseline() {
        // 10km 를 시내버스(20km/h)로 0.5시간, 기존 수단으로 0.4시간. 신설 30km/h 는 1/3시간.
        // 버스 기준이면 1/6시간을 아끼지만, 이미 0.4시간에 가는 사람은 1/15시간만 아낀다
        Long capped = calculator().benefitTotal(params(), 10_000, 10.0, 30.0, 0.4, true);

        double yearly = (0.4 - 10.0 / 30) * 10_000 * 365 * 10_000;
        assertThat(capped).isEqualTo(Math.round(yearly * DISCOUNT_SUM / 100_000_000.0));
    }

    @Test
    @DisplayName("기존 수단보다 느리면 시내버스보다 빨라도 편익이 0이다")
    void slowerThanExistingEarnsNothing() {
        // 서울~부산에서 광역급행 고가가 229분, 기존 KTX 가 164분인데 B/C 3.86 이 나왔다.
        // 기준이 시내버스(349km 를 약 16시간)뿐이라 KTX 를 타던 사람도 12.6시간을 아꼈다고 셌다
        assertThat(calculator().benefitTotal(params(), 10_000, 10.0, 30.0, 0.3, true)).isZero();
    }

    @Test
    @DisplayName("기존 수단이 시내버스보다 느리면 시내버스가 기준이다")
    void slowerExistingDoesNotRaiseBaseline() {
        // 역까지 멀어 문앞~문앞이 버스보다 오래 걸리면 그 사람은 버스를 탔을 것이다
        assertThat(calculator().benefitTotal(params(), 10_000, 10.0, 30.0, 2.0, true))
                .isEqualTo(904L);
    }

    @Test
    @DisplayName("기존 수단 시간을 모르면 지금처럼 시내버스만 본다")
    void unknownExistingKeepsBusBaseline() {
        assertThat(calculator().benefitTotal(params(), 10_000, 10.0, 30.0, null, true))
                .isEqualTo(904L);
    }

    /**
     * 전에는 이용객 **전원이 노선 끝에서 끝까지 탄다**고 봤다. 그러면 1인당 절감이 연장에
     * 비례해 커져 긴 노선의 B/C 가 부풀었다. 서울 지하철 1회 이동은 13.6km 다(서울시 2019).
     */
    @Test
    @DisplayName("긴 노선의 1인당 절감은 평균 통행거리만큼이다")
    void longRouteSavesOnlyAverageTrip() {
        // 30km 를 20 → 30km/h. 끝까지 타면 1/2시간, 13.6km 만 타면 13.6 × (1/20 − 1/30)
        Long benefit = calculator().benefitTotal(params(), 10_000, 30.0, 30.0);

        double yearly = 13.6 * (1.0 / 20 - 1.0 / 30) * 10_000 * 365 * 10_000;
        assertThat(benefit).isEqualTo(Math.round(yearly * DISCOUNT_SUM / 100_000_000.0));
    }

    @Test
    @DisplayName("평균 통행거리보다 짧은 노선은 끝까지 탄다")
    void shortRouteKeepsFullLength() {
        // 10km < 13.6km — 노선보다 길게 탈 수는 없다. 위 첫 테스트의 904억과 같다
        assertThat(calculator().benefitTotal(params(), 10_000, 10.0, 30.0)).isEqualTo(904L);
    }

    @Test
    @DisplayName("기존 수단은 노선 전체를 가는 시간의 속도로 평균 통행거리를 간다")
    void existingPaceAppliesToAverageTrip() {
        // 30km 를 기존 수단으로 1.2시간(시간당 25km) → 13.6km 는 0.544시간.
        // 신설 30km/h 로는 13.6/30 시간. 버스(13.6/20 = 0.68시간)보다 기존 수단이 빠르다
        Long benefit = calculator().benefitTotal(params(), 10_000, 30.0, 30.0, 1.2, true);

        double yearly = 13.6 * (1.2 / 30 - 1.0 / 30) * 10_000 * 365 * 10_000;
        assertThat(benefit).isEqualTo(Math.round(yearly * DISCOUNT_SUM / 100_000_000.0));
    }

    /**
     * KTX 는 역이 수십 km 마다 있어 13.6km 짜리 이동에는 탈 수 없다. 그런데 KTX 의 km 당
     * 시간(서울~부산 190분 ÷ 349km)을 짧은 이동에 그대로 쓰면 "KTX 보다 느려 절감 없음" 이라는
     * 말이 안 되는 이유로 B/C 0 이 나왔다. 역이 드문 수단은 통행이 노선 전체를 덮을 때만 쓴다.
     */
    @Test
    @DisplayName("짧은 이동에 못 타는 기존 수단은 노선 전체를 탈 때만 기준이 된다")
    void sparseExistingOnlyForFullTrips() {
        // 30km 노선, KTX 같은 수단이 0.3시간(시간당 100km). 13.6km 만 타면 못 탄다 → 버스 기준
        Long sparse = calculator().benefitTotal(params(), 10_000, 30.0, 30.0, 0.3, false);
        assertThat(sparse).isEqualTo(calculator().benefitTotal(params(), 10_000, 30.0, 30.0));

        // 노선이 평균 통행거리보다 짧아 끝까지 타면 탈 수 있다 → 그 수단보다 느려 0
        assertThat(calculator().benefitTotal(params(), 10_000, 10.0, 30.0, 0.1, false)).isZero();

        // 역이 촘촘한 수단(도시철도)은 짧은 이동에도 기준이 된다
        assertThat(calculator().benefitTotal(params(), 10_000, 30.0, 30.0, 0.3, true)).isZero();
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
    @DisplayName("첨두 단면은 평일 보정·첨두율·단면 환산을 모두 곱한다")
    void peakIsConvertedFromDailyRiders() {
        // 수요 모델은 주말 섞인 일 평균을 내놓는다 — 평일 설계 첨두까지 세 번 보정한다
        // 연장 45km 면 K = 9/45 = 0.2 → 100,000 × 1.1 × 0.1 × 0.2 = 2,200
        assertThat(calculator().peakPphpd(params(), 100_000, 45)).isEqualTo(2_200);
        assertThat(calculator().peakPphpd(params(), null, 45)).isNull();
    }

    @Test
    @DisplayName("단면 환산계수는 연장에 반비례하고 상한에서 멈춘다")
    void sectionRatioFallsWithLength() {
        BenefitCalculator c = calculator();
        // 통행이 노선보다 짧으면 한 지점을 지나는 비율이 (평균 통행거리 ÷ 연장)이다
        assertThat(c.sectionRatio(params(), 45)).isEqualTo(0.2);
        assertThat(c.sectionRatio(params(), 90)).isEqualTo(0.1);
        // 짧은 노선에서 1/L 이 발산하지 않게 상한이 잡아 준다 (9/20 = 0.45 > 0.3)
        assertThat(c.sectionRatio(params(), 20)).isEqualTo(0.3);
        assertThat(c.sectionRatio(params(), 0)).isEqualTo(0.3);
        // 긴 노선일수록 단면에 실리는 비율이 낮아야 한다
        assertThat(c.sectionRatio(params(), 60))
                .isLessThan(c.sectionRatio(params(), 30));
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
