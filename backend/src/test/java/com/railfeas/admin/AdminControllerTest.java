package com.railfeas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.common.ApiException;
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
}
