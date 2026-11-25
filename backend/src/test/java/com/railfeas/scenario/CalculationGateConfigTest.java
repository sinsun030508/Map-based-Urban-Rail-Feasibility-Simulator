package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 계산 한도가 **실제 설정 파일(`application.yml`)과 환경 변수로** 들어오는가(11-25 코드 리뷰). 설정 파일은 `app.calc.*` 에 두는데 게이트가 `railfeas.calc.*` 를
 * 읽어, 노트가 안내하는 `CALC_MAX_CONCURRENT`·`CALC_MAX_PER_USER` 를 바꿔도 늘 기본값(코어 절반·사용자당 2)으로 돌았다 — `CalculationGateTest` 는 게이트를
 * `new` 로 만들어 이 연결을 못 봤다. 게이트 하나만 띄운다(자동 설정 없음 — DB·Redis 없이 설정 파일만 읽는다).
 */
class CalculationGateConfigTest {

    @Nested
    @SpringBootTest(classes = CalculationGate.class, properties = "CALC_MAX_PER_USER=1")
    @DisplayName("CALC_MAX_PER_USER=1 이면 같은 사용자의 둘째 계산이 429")
    class PerUser {
        @Autowired
        CalculationGate gate;

        @Test
        void perUserLimitComesFromTheEnvironment() {
            try (CalculationGate.Permit first = gate.enter(1L)) {
                assertThatThrownBy(() -> gate.enter(1L)).isInstanceOf(ApiException.class).hasMessageContaining("이미");
            }
        }
    }

    @Nested
    @SpringBootTest(classes = CalculationGate.class, properties = "CALC_MAX_CONCURRENT=1")
    @DisplayName("CALC_MAX_CONCURRENT=1 이면 다른 사용자의 둘째 계산도 429")
    class Global {
        @Autowired
        CalculationGate gate;

        @Test
        void globalLimitComesFromTheEnvironment() {
            try (CalculationGate.Permit first = gate.enter(1L)) {
                assertThatThrownBy(() -> gate.enter(2L)).isInstanceOf(ApiException.class).hasMessageContaining("몰려");
            }
        }
    }
}
