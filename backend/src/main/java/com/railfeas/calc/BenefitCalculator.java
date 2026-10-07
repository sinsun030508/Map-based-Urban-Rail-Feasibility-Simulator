package com.railfeas.calc;

import com.railfeas.reference.BenefitParameter;
import com.railfeas.reference.BenefitParameterRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 수요 → 편익 → B/C.
 *
 * 수요  log(일이용객) = a + b1·log(인구) + b2·log(종사자) + b3·log(환승노선) + b4·도심거리
 *       계수는 etl/demand_model.py 산출(S6). **교차검증 1.75배 오차**를 안고 있다.
 * 편익  전환 전 수단 대비 통행시간 절감 × 이용객 × 시간가치.
 *       차량운행비·사고·환경 편익은 이번 범위 밖 (제안서 한계에 명시).
 * B/C   편익의 현재가치 ÷ 사업비의 현재가치 (지침 6.3.1).
 *
 * 현재가치는 투자평가지침 제7판을 따른다.
 *   기준시점은 공사 착수 직전이고, 편익은 공사 5년이 끝난 뒤부터 40년간 발생한다(6.2.2).
 *   할인율은 개통 후 30년까지 4.5%, 31~40년은 3.5%(6.2.1, 6.3.1 ②).
 *   사업비는 표 6-1 의 연차별 투입비율대로 공사기간에 걸쳐 투입된다.
 * 원단위는 S7 참고. 시간가치·할인율·분석기간은 지침값이고
 * 첨두율·기준속도는 지침에 없어 가정값이다.
 */
@Component
public class BenefitCalculator {

    private static final double EOK = 100_000_000.0;   // 1억원

    /**
     * 공사기간 5년의 연차별 사업비 투입비율 — 지침 표 6-1 (431쪽).
     * 공사기간을 5년이 아니게 바꾸면 이 표가 맞지 않으므로 균등 투입으로 돌린다.
     */
    private static final double[] COST_SHARE_5Y = {0.10, 0.20, 0.30, 0.30, 0.10};

    private final BenefitParameterRepository parameters;

    public BenefitCalculator(BenefitParameterRepository parameters) {
        this.parameters = parameters;
    }

    public Params load() {
        Map<String, Double> values = parameters.findAll().stream()
                .collect(Collectors.toMap(BenefitParameter::getParamName,
                        p -> p.getValue().doubleValue(), (a, b) -> a));
        return new Params(values);
    }

    /** 노선 주변 인구·종사자로 일 이용객을 추정한다. 값이 없으면 null. */
    public Integer estimateDailyRiders(Params p, long population, long workers,
                                       double cbdDistanceKm) {
        if (population <= 0 || !p.hasDemandModel()) {
            return null;
        }
        double log = p.get("demand_intercept")
                + p.get("demand_pop_elasticity") * Math.log(population)
                + p.get("demand_worker_elasticity") * Math.log(workers + 1.0)
                + p.get("demand_cbd_slope") * cbdDistanceKm;
        // 환승 노선 수는 신설 노선이라 1 로 본다 (log 1 = 0) — 기존 노선과의 환승은 미반영
        return (int) Math.round(Math.exp(log));
    }

    /**
     * 평일 첨두시 한 방향 최대 단면 통행량. 수송능력 판정에 쓴다.
     *
     * 수요 모델이 내놓는 것은 **주말이 섞인 일 평균**이라 세 번 보정한다.
     *   ① 평일 일수요 배율 — 주말이 평일의 69% 라서 평균이 낮게 잡힌다
     *   ② 평일 첨두율 — 평일은 8시·18시에 몰리고 주말은 16시에 완만하다
     *   ③ 역 승하차 → 단면 변환 — 한 통행을 승차·하차로 두 번 세고, 모두가 최대 단면을
     *      지나지도 않는다 (혼잡도 실측에서 역산)
     * 셋을 따로 재 두지 않으면 하나만 고쳐도 나머지가 조용히 어긋난다.
     */
    public Integer peakPphpd(Params p, Integer dailyRiders, double lengthKm) {
        if (dailyRiders == null) {
            return null;
        }
        return (int) Math.round(dailyRiders
                * p.get("weekday_factor")
                * p.get("peak_hour_ratio")
                * sectionRatio(p, lengthKm));
    }

    /**
     * 역 승하차를 최대 단면 통행량으로 바꾸는 계수. **연장의 함수다.**
     *
     * 통행이 노선보다 짧으면 한 지점을 지나는 통행의 비율은 대략 `평균 통행거리 ÷ 연장`
     * 이라, 긴 노선일수록 단면에 실리는 비율이 낮다. 상수로 두면 30~60km 노선에서 잰
     * 값을 5~35km 노선에 적용하게 되어 **짧은 노선의 단면을 과소 추정**한다
     * (서울 1~8호선 실측 r=0.86, 교차검증에서 상수보다 오차 44% 낮다).
     *
     * 상한이 있다. 모든 통행이 최대 단면을 지나더라도 승하차 이중 계산(×0.5)과
     * 방향 쏠림(실측 0.79)을 넘을 수 없다. 이 뚜껑이 없으면 짧은 노선에서 발산한다.
     *
     * **표본이 30~60km 4개뿐이라 그보다 짧은 구간은 외삽이다** — 25.5km 아래는 전부
     * 상한에 걸린다. 근거는 etl/seoul_congestion.py 와 docs/research-log.md.
     */
    double sectionRatio(Params p, double lengthKm) {
        double ceiling = p.get("peak_direction_max");
        if (lengthKm <= 0) {
            return ceiling;
        }
        return Math.min(ceiling, p.get("peak_direction_coef") / lengthKm);
    }

    /**
     * 통행시간 절감편익의 현재가치(억원). 전환 전 수단보다 빨라진 만큼만 센다.
     * 수단이 기존 수단보다 느리면 편익은 0 으로 둔다 (음의 편익은 계산하지 않는다).
     */
    public Long benefitTotal(Params p, Integer dailyRiders, double lengthKm, double speedKmh) {
        if (dailyRiders == null || speedKmh <= 0) {
            return null;
        }
        double baseline = p.get("baseline_speed_kmh");
        double savedHours = lengthKm / baseline - lengthKm / speedKmh;
        if (savedHours <= 0) {
            return 0L;
        }
        double yearly = savedHours * dailyRiders * 365 * p.get("time_value_won_per_hour");
        int years = (int) p.get("analysis_years");
        double sum = 0;
        for (int y = 1; y <= years; y++) {
            sum += yearly * benefitDiscountFactor(p, y);
        }
        return Math.round(sum / EOK);
    }

    /**
     * 개통 후 y 년째 편익을 기준시점으로 끌어오는 할인계수.
     *
     * 기준시점이 공사 착수 직전이라 공사기간만큼 더 할인된다(지침 6.2.2 (4) — 편익은
     * 완공 후 공용시점부터). 개통 후 30년까지는 4.5%로 할인하고, 31~40년 구간은
     * 30년치 4.5% 할인에 이어 3.5%를 적용한다(지침 6.3.1 ②).
     */
    private double benefitDiscountFactor(Params p, int yearAfterOpening) {
        double early = p.get("discount_rate");
        double late = p.get("discount_rate_late");
        int switchYear = (int) p.get("discount_switch_year");
        double construction = p.get("construction_years");

        double divisor = Math.pow(1 + early,
                construction + Math.min(yearAfterOpening, switchYear));
        if (yearAfterOpening > switchYear) {
            divisor *= Math.pow(1 + late, yearAfterOpening - switchYear);
        }
        return 1 / divisor;
    }

    /**
     * 총사업비의 현재가치(억원). 공사기간에 걸쳐 나눠 투입되므로 명목 총액보다 작다.
     * 투입비율은 지침 표 6-1, 할인율은 공사기간 중이라 4.5%를 쓴다.
     */
    public long costPresentValue(Params p, long costEok) {
        double rate = p.get("discount_rate");
        int years = Math.max(1, (int) p.get("construction_years"));
        double sum = 0;
        for (int y = 1; y <= years; y++) {
            double share = years == COST_SHARE_5Y.length
                    ? COST_SHARE_5Y[y - 1]
                    : 1.0 / years;
            sum += costEok * share / Math.pow(1 + rate, y);
        }
        return Math.round(sum);
    }

    /** 편익·비용 모두 현재가치로 넣어야 한다 (지침 6.3.1). */
    public BigDecimal bcRatio(Long benefitEok, long costPvEok) {
        if (benefitEok == null || costPvEok <= 0) {
            return null;
        }
        return BigDecimal.valueOf((double) benefitEok / costPvEok)
                .setScale(3, RoundingMode.HALF_UP);
    }

    /** benefit_parameter 를 한 번만 읽어 쓰기 위한 묶음. 없는 값은 즉시 드러나게 예외. */
    public static class Params {
        private final Map<String, Double> values;

        Params(Map<String, Double> values) {
            this.values = values;
        }

        public double get(String name) {
            Double v = values.get(name);
            if (v == null) {
                throw new IllegalStateException(
                        "benefit_parameter 에 " + name + " 이 없습니다 — S6·S7 적재를 확인하세요");
            }
            return v;
        }

        public boolean hasDemandModel() {
            return values.containsKey("demand_intercept");
        }
    }
}
