package com.railfeas.calc;

import com.railfeas.reference.BenefitParameter;
import com.railfeas.reference.BenefitParameterRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 수요 → 편익 → B/C.
 *
 * 수요  log(일이용객) = a + b1·log(인구) + b2·log(종사자) + b3·log(환승노선) + b4·도심거리
 *       계수는 etl/demand_model.py 산출(S6). **교차검증 1.75배 오차**를 안고 있다.
 * 편익  전환 전 수단 대비 통행시간 절감 × 이용객 × 시간가치. 분석기간 동안 할인해 합산한다.
 *       차량운행비·사고·환경 편익은 이번 범위 밖 (제안서 한계에 명시).
 * B/C   편익 합계 ÷ 총사업비.
 *
 * **원단위가 아직 미확정이다** (S7 참고). 구조만 맞춰 둔 상태라 숫자를 인용하면 안 된다.
 */
@Component
public class BenefitCalculator {

    private static final double EOK = 100_000_000.0;   // 1억원

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

    /** 첨두시 한 방향 최대 이용객. 수송능력 판정에 쓴다. */
    public Integer peakPphpd(Params p, Integer dailyRiders) {
        if (dailyRiders == null) {
            return null;
        }
        return (int) Math.round(dailyRiders
                * p.get("peak_hour_ratio") * p.get("peak_direction_ratio"));
    }

    /**
     * 통행시간 절감편익(억원). 전환 전 수단보다 빨라진 만큼만 센다.
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
        double rate = p.get("discount_rate");
        int years = (int) p.get("analysis_years");
        double sum = 0;
        for (int y = 1; y <= years; y++) {
            sum += yearly / Math.pow(1 + rate, y);
        }
        return Math.round(sum / EOK);
    }

    public BigDecimal bcRatio(Long benefitEok, long costEok) {
        if (benefitEok == null || costEok <= 0) {
            return null;
        }
        return BigDecimal.valueOf((double) benefitEok / costEok)
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

        public Function<String, Double> asFunction() {
            return this::get;
        }
    }
}
