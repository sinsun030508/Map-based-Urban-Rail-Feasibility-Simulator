package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.calc.CostCalculator;
import com.railfeas.calc.ExistingOptions;
import com.railfeas.common.ApiException;
import com.railfeas.common.PointType;
import com.railfeas.common.StructureType;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.CostStandardRepository;
import com.railfeas.reference.ModeCapacityRepository;
import com.railfeas.reference.RestrictedZoneRepository;
import com.railfeas.user.AuthProvider;
import com.railfeas.user.Role;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 계산이 **동시 실행 한도**(`CalculationGate`)를 지키는지 — 11-22 최악 입력 시험에서 한 사용자의 30개 동시 요청이 10개를 500 으로, 다른 사용자의 목록 조회를 51초 막았다.
 * 한도가 차면 **계산기를 아예 부르지 않고** 429 여야 하고(CPU·DB 연결을 안 쓴다), **어떤 경로로 끝나도 허가를 돌려줘야** 한다(새면 서버가 점점 계산을 거부한다).
 */
class ScenarioCalculateGateTest {

    private static final long OWNER = 7L, SCENARIO = 1L;

    private final ScenarioCalculateTransactionTest.RecordingTxManager tx = new ScenarioCalculateTransactionTest.RecordingTxManager();
    private final ScenarioRepository scenarios = Mockito.mock(ScenarioRepository.class);
    private final CostCalculator calculator = Mockito.mock(CostCalculator.class);
    private final StationBatchWriter stationWriter = Mockito.mock(StationBatchWriter.class);
    private Scenario scenario;

    private static RoutePoint point(int seq, double lat, double lng, PointType type) {
        return RoutePoint.builder().sequence(seq).latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng)).pointType(type).build();
    }

    @BeforeEach
    void setUp() {
        User owner = User.builder().email("o@example.test").password("해시").nickname("주인").provider(AuthProvider.LOCAL).role(Role.USER).build();
        ReflectionTestUtils.setField(owner, "id", OWNER);
        scenario = Scenario.builder().user(owner).title("시험").totalLengthKm(BigDecimal.valueOf(8)).preferredStructure(StructureType.ELEVATED).build();
        scenario.replacePoints(List.of(point(0, 37.50, 127.00, PointType.START), point(1, 37.55, 127.05, PointType.END)), BigDecimal.valueOf(8));
        Mockito.when(scenarios.findWithDetailsById(SCENARIO)).thenReturn(Optional.of(scenario));
        Mockito.when(scenarios.lockOwned(SCENARIO, OWNER)).thenReturn(Optional.of(scenario));
    }

    private ScenarioService service(CalculationGate gate) {
        CostStandardRepository standards = Mockito.mock(CostStandardRepository.class);
        Mockito.when(standards.findByActiveTrue()).thenReturn(List.of(Mockito.mock(CostStandard.class)));
        ModeCapacityRepository capacities = Mockito.mock(ModeCapacityRepository.class);
        Mockito.when(capacities.findAll()).thenReturn(List.of());
        RestrictedZoneRepository zones = Mockito.mock(RestrictedZoneRepository.class);
        Mockito.when(zones.findAll()).thenReturn(List.of());
        return new ScenarioService(scenarios, Mockito.mock(UserRepository.class), standards, capacities, zones, calculator,
                Mockito.mock(ExistingOptions.class), Mockito.mock(EntityManager.class), tx, gate, stationWriter);
    }

    private void calculatorReturnsOutcome() {
        Mockito.when(calculator.calculate(Mockito.any(), Mockito.anyList(), Mockito.anyMap(), Mockito.anyList()))
                .thenReturn(new CostCalculator.Outcome(List.of(), 1L, null, null));
    }

    @Test
    @DisplayName("한도가 차 있으면 계산기를 부르지 않고 429 다 — CPU·DB 연결을 쓰지 않는다")
    void fullGateRejectsWithoutCalculating() {
        CalculationGate gate = new CalculationGate(1, 1);
        CalculationGate.Permit held = gate.enter(OWNER);       // 이미 계산 중인 한 건

        assertThatThrownBy(() -> service(gate).calculate(OWNER, SCENARIO))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        Mockito.verifyNoInteractions(calculator);
        Mockito.verify(scenarios, Mockito.never()).findWithDetailsById(Mockito.anyLong());     // 읽기 트랜잭션도 열지 않았다
        assertThat(tx.readOnlyOfBegun).as("트랜잭션을 하나도 열지 않았다").isEmpty();
        held.close();
    }

    @Test
    @DisplayName("정상으로 끝나면 허가를 돌려준다 — 이어서 같은 사용자가 다시 계산할 수 있다")
    void releasesPermitAfterSuccess() {
        calculatorReturnsOutcome();
        CalculationGate gate = new CalculationGate(1, 1);
        ScenarioService service = service(gate);

        service.calculate(OWNER, SCENARIO);
        service.calculate(OWNER, SCENARIO);          // 한도가 1 인데 두 번째가 들어간다 = 첫 번째가 돌려줬다

        assertThat(gate.enter(OWNER)).isNotNull();   // 지금도 비어 있다
    }

    @Test
    @DisplayName("계산이 예외로 끝나도 허가를 돌려준다 — 새면 서버가 점점 계산을 거부한다")
    void releasesPermitAfterFailure() {
        Mockito.when(calculator.calculate(Mockito.any(), Mockito.anyList(), Mockito.anyMap(), Mockito.anyList()))
                .thenThrow(new IllegalStateException("계산 중 오류"));
        CalculationGate gate = new CalculationGate(1, 1);
        ScenarioService service = service(gate);

        assertThatThrownBy(() -> service.calculate(OWNER, SCENARIO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.calculate(OWNER, SCENARIO)).isInstanceOf(IllegalStateException.class);   // 둘째도 들어갔다 = 429 가 아니다

        gate.enter(OWNER).close();      // 비어 있다
    }

    @Test
    @DisplayName("노선이 바뀌어 409 가 나도 허가를 돌려준다")
    void releasesPermitAfterConflict() {
        Mockito.when(calculator.calculate(Mockito.any(), Mockito.anyList(), Mockito.anyMap(), Mockito.anyList())).thenAnswer(inv -> {
            scenario.replacePoints(List.of(point(0, 35.10, 129.00, PointType.START), point(1, 35.20, 129.10, PointType.END)), BigDecimal.valueOf(15));
            return new CostCalculator.Outcome(List.of(), 1L, null, null);
        });
        CalculationGate gate = new CalculationGate(1, 1);

        assertThatThrownBy(() -> service(gate).calculate(OWNER, SCENARIO))
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        gate.enter(OWNER).close();
    }
}
