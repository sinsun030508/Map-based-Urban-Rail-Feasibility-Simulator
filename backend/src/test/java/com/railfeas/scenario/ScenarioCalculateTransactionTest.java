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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * **계산하는 동안 DB 연결을 잡지 않는다**(11-22 코드 점검). 계산은 1~7초(서울~부산 3초)인데 `calculate` 전체가 한 트랜잭션이라 그동안 연결 하나가 묶였다 —
 * `open-in-view` 가 꺼져 있어 연결 풀(기본 10)을 계산 열 개가 다 차지하면 로그인·목록까지 막힌다. 그래서 읽기(읽기 전용) → 계산(트랜잭션 밖) → 쓰기 셋으로 나눴다.
 * 나눈 값은 **계산하는 동안 노선이 바뀌면 낡은 결과를 새 노선에 붙이지 않는다**(409).
 */
class ScenarioCalculateTransactionTest {

    private static final long OWNER = 7L, SCENARIO = 1L;

    /** 시작·끝을 기록하는 트랜잭션 관리자 — 계산기가 불릴 때 트랜잭션이 켜져 있었는지 본다 */
    static class RecordingTxManager extends AbstractPlatformTransactionManager {
        final List<Boolean> readOnlyOfBegun = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            readOnlyOfBegun.add(definition.isReadOnly());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    private final RecordingTxManager tx = new RecordingTxManager();
    private final ScenarioRepository scenarios = Mockito.mock(ScenarioRepository.class);
    private final CostStandardRepository standards = Mockito.mock(CostStandardRepository.class);
    private final CostCalculator calculator = Mockito.mock(CostCalculator.class);
    private final EntityManager entityManager = Mockito.mock(EntityManager.class);
    private Scenario scenario;
    private ScenarioService service;

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
        Mockito.when(standards.findByActiveTrue()).thenReturn(List.of(Mockito.mock(CostStandard.class)));
        Mockito.when(standards.findAll()).thenReturn(List.of());
        ModeCapacityRepository capacities = Mockito.mock(ModeCapacityRepository.class);
        Mockito.when(capacities.findAll()).thenReturn(List.of());
        RestrictedZoneRepository zones = Mockito.mock(RestrictedZoneRepository.class);
        Mockito.when(zones.findAll()).thenReturn(List.of());
        service = new ScenarioService(scenarios, Mockito.mock(UserRepository.class), standards, capacities, zones, calculator,
                Mockito.mock(ExistingOptions.class), entityManager, tx, new CalculationGate(4, 4));
    }

    private CostCalculator.Outcome outcome() {
        return new CostCalculator.Outcome(List.of(), 12_345L, null, null);
    }

    @Test
    @DisplayName("계산기가 불릴 때 트랜잭션이 켜져 있지 않다 — 읽기(읽기 전용) · 계산 · 쓰기, 트랜잭션은 둘")
    void calculationRunsOutsideAnyTransaction() {
        List<Boolean> activeDuringCalculation = new ArrayList<>();
        Mockito.when(calculator.calculate(Mockito.any(), Mockito.anyList(), Mockito.anyMap(), Mockito.anyList())).thenAnswer(inv -> {
            activeDuringCalculation.add(TransactionSynchronizationManager.isActualTransactionActive());
            return outcome();
        });

        service.calculate(OWNER, SCENARIO);

        assertThat(activeDuringCalculation).as("계산하는 동안 연결을 잡고 있으면 안 된다").containsExactly(false);
        assertThat(tx.readOnlyOfBegun).as("읽기 한 번(읽기 전용) · 쓰기 한 번").containsExactly(true, false);
    }

    @Test
    @DisplayName("정상이면 결과가 붙고 계산 시각이 찍힌다 — 옛 결과 삭제 flush 가 새 결과 추가보다 먼저다")
    void resultsAreAppliedAfterCalculation() {
        Mockito.when(calculator.calculate(Mockito.any(), Mockito.anyList(), Mockito.anyMap(), Mockito.anyList())).thenReturn(outcome());

        service.calculate(OWNER, SCENARIO);

        assertThat(scenario.getPopulation1km()).isEqualTo(12_345L);
        assertThat(scenario.getCalculatedAt()).isNotNull();
        Mockito.verify(entityManager).flush();
    }

    /**
     * **같은 시나리오의 계산 쓰기 단계는 줄을 선다**(11-22 동시 계산 시험). 같은 시나리오를 동시에 계산하면 쓰기 트랜잭션 둘이 서로 옛 결과 삭제·새 결과 삽입을 기다려
     * MySQL 교착이 나 8개 중 7개가 500 이었다. 쓰기 트랜잭션의 **첫 문장으로 행을 잠가**(`lockOwned`) 나머지가 교착 대신 순서를 기다리게 한다 — 잠근 **뒤에** 읽어야
     * 앞 요청이 커밋한 결과를 보므로(REPEATABLE READ 스냅샷) 순서가 중요하다.
     */
    @Test
    @DisplayName("쓰기 단계는 쓰기 트랜잭션 안에서 시나리오 행을 먼저 잠그고, 그다음에 노선을 읽는다")
    void writePhaseLocksTheScenarioRowFirst() {
        Mockito.when(calculator.calculate(Mockito.any(), Mockito.anyList(), Mockito.anyMap(), Mockito.anyList())).thenReturn(outcome());
        List<Boolean> lockedInWritableTx = new ArrayList<>();
        Mockito.when(scenarios.lockOwned(SCENARIO, OWNER)).thenAnswer(inv -> {
            lockedInWritableTx.add(TransactionSynchronizationManager.isActualTransactionActive()
                    && !TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return Optional.of(scenario);
        });

        service.calculate(OWNER, SCENARIO);

        // 읽기 단계(잠그지 않는다 — 읽기 전용) → 쓰기 단계(잠금 → 읽기)
        var order = Mockito.inOrder(scenarios);
        order.verify(scenarios).findWithDetailsById(SCENARIO);
        order.verify(scenarios).lockOwned(SCENARIO, OWNER);
        order.verify(scenarios).findWithDetailsById(SCENARIO);
        assertThat(lockedInWritableTx).as("잠금은 쓰기 가능한 트랜잭션 안에서 딱 한 번").containsExactly(true);
    }

    @Test
    @DisplayName("계산하는 동안 노선이 바뀌면 409 — 낡은 결과를 새 노선에 붙이지 않는다")
    void routeChangedDuringCalculationIsConflict() {
        Mockito.when(calculator.calculate(Mockito.any(), Mockito.anyList(), Mockito.anyMap(), Mockito.anyList())).thenAnswer(inv -> {
            // 사용자가 그사이 다른 노선으로 고쳐 저장했다
            scenario.replacePoints(List.of(point(0, 35.10, 129.00, PointType.START), point(1, 35.20, 129.10, PointType.END)), BigDecimal.valueOf(15));
            return outcome();
        });

        assertThatThrownBy(() -> service.calculate(OWNER, SCENARIO))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("바뀌");
        assertThat(scenario.getCalculatedAt()).as("결과를 붙이지 않았다").isNull();
        Mockito.verify(entityManager, Mockito.never()).flush();
    }
}
