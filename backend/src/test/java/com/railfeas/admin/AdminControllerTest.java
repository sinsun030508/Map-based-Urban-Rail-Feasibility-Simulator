package com.railfeas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.common.ApiException;
import com.railfeas.common.ModeType;
import com.railfeas.reference.ModeCapacity;
import com.railfeas.reference.ModeCapacityRepository;
import com.railfeas.reference.BenefitParameter;
import com.railfeas.reference.BenefitParameterRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 관리자가 고치는 원단위 중 **0 이하가 되면 계산이 조용히 망가지는 값**을 막는다.
 * 평균 통행거리를 0 으로 두면 모든 편익이 0 이 되고, 기준 속도를 0 으로 두면 0 으로 나눈다.
 * 화면에는 오류 없이 B/C 0.00 만 줄지어 찍힌다 — 400 으로 막아야 무엇이 틀렸는지 보인다.
 */
class AdminControllerTest {

    private static AdminController controller(String name, double value) {
        BenefitParameterRepository repo = Mockito.mock(BenefitParameterRepository.class);
        Mockito.when(repo.findByParamName(name)).thenReturn(Optional.of(parameter(name, value)));
        return new AdminController(repo, null);
    }

    private static BenefitParameter parameter(String name, double value) {
        try {
            Constructor<BenefitParameter> c = BenefitParameter.class.getDeclaredConstructor();
            c.setAccessible(true);
            BenefitParameter p = c.newInstance();
            for (String[] f : new String[][]{{"paramName", name}, {"source", "시드"}}) {
                Field field = BenefitParameter.class.getDeclaredField(f[0]);
                field.setAccessible(true);
                field.set(p, f[1]);
            }
            Field v = BenefitParameter.class.getDeclaredField("value");
            v.setAccessible(true);
            v.set(p, BigDecimal.valueOf(value));
            return p;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AdminController.ParameterRequest request(double value) {
        return new AdminController.ParameterRequest(BigDecimal.valueOf(value), "테스트");
    }

    @Test
    @DisplayName("평균 통행거리와 기준 속도는 0 이하로 바꿀 수 없다")
    void 양수여야_하는_값() {
        for (String name : new String[]{"avg_trip_km", "baseline_speed_kmh"}) {
            assertThatThrownBy(() -> controller(name, 13.6).updateParameter(name, request(0)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining(name);
            assertThatThrownBy(() -> controller(name, 13.6).updateParameter(name, request(-1)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("0 이 정상인 값은 0 으로 바꿀 수 있다")
    void 영이_정상인_값() {
        // 운영비 비율은 시드부터 0 이다 — 모든 원단위에 양수를 강요하면 이 값을 못 저장한다
        var saved = controller("operating_cost_ratio", 0.1)
                .updateParameter("operating_cost_ratio", request(0));
        assertThat(saved.value()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("양수로 바꾸는 것은 그대로 된다")
    void 양수는_된다() {
        var saved = controller("avg_trip_km", 13.6).updateParameter("avg_trip_km", request(12.0));
        assertThat(saved.value()).isEqualByComparingTo("12");
    }

    /**
     * 반복문이 분석·공사 기간만큼 돈다 — 10억 년을 넣으면 계산이 끝나지 않는다. 할인율이 음수거나
     * 100% 이상이면 할인이 뜻을 잃고, 비율이 1 을 넘을 수는 없다(11-04 — 둘만 막고 있었다)
     */
    @Test
    @DisplayName("원단위마다 허용 범위가 있다 — 기간·할인율·비율")
    void 원단위_범위() {
        String[][] bad = {
                {"analysis_years", "1000000000"}, {"analysis_years", "0"}, {"analysis_years", "101"},
                {"construction_years", "0"}, {"construction_years", "21"},
                {"discount_rate", "-0.01"}, {"discount_rate", "1"}, {"discount_rate_late", "1.5"},
                {"peak_hour_ratio", "0"}, {"peak_hour_ratio", "1.2"},
                {"operating_cost_ratio", "1"}, {"time_value_won_per_hour", "0"},
                // 이용자 ÷ 승하차 합 — 0 이면 편익이 전부 0, 1 을 넘으면 통행보다 이용자가 많다
                {"users_per_ridership", "0"}, {"users_per_ridership", "1.2"},
                // BRT 배수는 계수가 아니라 이용객에 곱하는 값 — 0·음수면 이용객이 0·음수다
                {"demand_brt_factor", "0"}, {"demand_brt_factor", "-0.3"},
        };
        for (String[] c : bad) {
            assertThatThrownBy(() -> controller(c[0], 1).updateParameter(c[0], request(Double.parseDouble(c[1]))))
                    .as("%s = %s", c[0], c[1])
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining(c[0]);
        }
        assertThat(controller("analysis_years", 40).updateParameter("analysis_years", request(100)).value())
                .isEqualByComparingTo("100");
        assertThat(controller("discount_rate", 0.045).updateParameter("discount_rate", request(0)).value())
                .isEqualByComparingTo("0");
        assertThat(controller("users_per_ridership", 0.777).updateParameter("users_per_ridership", request(1)).value())
                .isEqualByComparingTo("1");
    }

    /** 범위 표의 이름이 틀리면 그 범위는 **조용히** 안 걸린다 — 시드와 맞춰 본다 */
    @Test
    @DisplayName("범위 표의 이름은 모두 시드에 있고, 시드 값은 모두 범위 안이다")
    void 범위표와_시드() throws Exception {
        var seed = new java.util.HashMap<String, BigDecimal>();
        var row = java.util.regex.Pattern.compile("\\('([a-z_]+)',\\s*(-?[0-9.]+)\\s*,");
        // 수요 값(demand_*)은 S6 에서 온다 — demand_brt_factor 처럼 범위가 있는 것도 있어 둘 다 읽는다
        for (String file : new String[]{"S7__benefit_parameter.sql", "S6__demand_model.sql"}) {
            var m = row.matcher(java.nio.file.Files.readString(java.nio.file.Path.of("../db/seed/" + file)));
            while (m.find()) {
                seed.put(m.group(1), new BigDecimal(m.group(2)));
            }
        }
        // 정규식이 행을 놓치면 아래가 빈 채로 통과한다
        assertThat(seed).hasSizeGreaterThanOrEqualTo(AdminController.RANGES.size());
        assertThat(seed.keySet()).containsAll(AdminController.RANGES.keySet());
        AdminController.RANGES.forEach((name, range) ->
                assertThat(range.contains(seed.get(name))).as("%s = %s", name, seed.get(name)).isTrue());
    }

    @Test
    @DisplayName("수요 회귀 계수는 음수도 된다 — 범위를 두지 않는다")
    void 회귀_계수는_자유() {
        assertThat(controller("demand_intercept", 2.79).updateParameter("demand_intercept", request(-5)).value())
                .isEqualByComparingTo("-5");
        assertThat(controller("demand_light_rail_coef", -0.6).updateParameter("demand_light_rail_coef", request(-2)).value())
                .isEqualByComparingTo("-2");
    }

    // ── 수단 기준값 ─────────────────────────────────────────────────────────────
    // 역간격이 0·음수면 역 수가 조용히 「종점 2개」 로 바뀌고, 0.0001 처럼 아주 작으면 10km 에 역이
    // 10만 개 생겨 계산 한 번이 몇 분이다(11-04 관리자 검증 점검 — 그때까지 아무 검사도 없었다)

    private static AdminController modeController() {
        ModeCapacityRepository repo = Mockito.mock(ModeCapacityRepository.class);
        try {
            Constructor<ModeCapacity> c = ModeCapacity.class.getDeclaredConstructor();
            c.setAccessible(true);
            ModeCapacity m = c.newInstance();
            Field f = ModeCapacity.class.getDeclaredField("modeType");
            f.setAccessible(true);
            f.set(m, ModeType.HEAVY_METRO);
            Mockito.when(repo.findById(ModeType.HEAVY_METRO)).thenReturn(Optional.of(m));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return new AdminController(null, repo);
    }

    private static AdminController.ModeRequest mode(Integer min, Integer max, Double spacing,
                                                    Double speed, Double maxLength) {
        return new AdminController.ModeRequest(min, max,
                spacing == null ? null : BigDecimal.valueOf(spacing),
                speed == null ? null : BigDecimal.valueOf(speed),
                maxLength == null ? null : BigDecimal.valueOf(maxLength), "테스트");
    }

    @Test
    @DisplayName("역간격은 열차 한 편성 길이(0.2km)보다 짧을 수 없다")
    void 역간격_하한() {
        for (double bad : new double[]{0, -1, 0.1, 0.0001}) {
            assertThatThrownBy(() -> modeController().updateMode(ModeType.HEAVY_METRO,
                    mode(20000, 80000, bad, 32.4, 60.2)))
                    .as("역간격 %s", bad)
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("역간격");
        }
        var saved = modeController().updateMode(ModeType.HEAVY_METRO, mode(20000, 80000, 0.2, 32.4, 60.2));
        assertThat(saved.spacingKm()).isEqualByComparingTo("0.2");
    }

    @Test
    @DisplayName("표정속도·권장 연장은 0 보다 크고, 수송능력은 음수일 수 없다")
    void 수단_값의_범위() {
        assertThatThrownBy(() -> modeController().updateMode(ModeType.HEAVY_METRO,
                mode(20000, 80000, 1.06, 0.0, 60.2))).hasMessageContaining("표정속도");
        assertThatThrownBy(() -> modeController().updateMode(ModeType.HEAVY_METRO,
                mode(20000, 80000, 1.06, 32.4, -5.0))).hasMessageContaining("권장 연장");
        assertThatThrownBy(() -> modeController().updateMode(ModeType.HEAVY_METRO,
                mode(-1, 80000, 1.06, 32.4, 60.2))).hasMessageContaining("수요 하한");
        assertThatThrownBy(() -> modeController().updateMode(ModeType.HEAVY_METRO,
                mode(null, -1, 1.06, 32.4, 60.2))).hasMessageContaining("수송능력");
    }

    @Test
    @DisplayName("비우는 것(NULL)은 그대로 된다 — 근거가 없으면 비워 두는 것이 정상이다")
    void 비우기는_된다() {
        var saved = modeController().updateMode(ModeType.HEAVY_METRO, mode(null, null, null, null, null));
        assertThat(saved.spacingKm()).isNull();
        assertThat(saved.maxLengthKm()).isNull();
    }
}
