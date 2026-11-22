package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.calc.CostCalculator;
import com.railfeas.calc.ExistingOptions;
import com.railfeas.common.ApiException;
import com.railfeas.common.PointType;
import com.railfeas.common.StructureType;
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
 * **수정·삭제도 시나리오 행을 먼저 잠근다**(11-22 동시 시험). 같은 시나리오를 동시에 수정하면 둘 다 옛 점을 읽고 지우고 다시 넣다가 두 번째가 이미 지워진 점을 또 지워
 * `OptimisticLockException` → 500 이었다(4개 중 3개). 계산 쓰기 단계와 같은 잠금(`lockOwned`)을 **첫 문장**으로 걸어 줄을 세운다. 교착·경주 자체는 실제 MySQL 에서만 나므로
 * 여기서는 **순서와 쓰기 트랜잭션 안**임, 남의 시나리오는 잠그지 않음만 고정한다 — 증거는 동시 시험(조사 기록·노트).
 */
class ScenarioWriteLockTest {

    private static final long OWNER = 7L, STRANGER = 8L, SCENARIO = 1L;

    private final ScenarioCalculateTransactionTest.RecordingTxManager tx = new ScenarioCalculateTransactionTest.RecordingTxManager();
    private final ScenarioRepository scenarios = Mockito.mock(ScenarioRepository.class);
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
        ModeCapacityRepository capacities = Mockito.mock(ModeCapacityRepository.class);
        service = new ScenarioService(scenarios, Mockito.mock(UserRepository.class), Mockito.mock(CostStandardRepository.class), capacities,
                Mockito.mock(RestrictedZoneRepository.class), Mockito.mock(CostCalculator.class), Mockito.mock(ExistingOptions.class),
                Mockito.mock(EntityManager.class), tx, new CalculationGate(4, 4));
    }

    private static ScenarioDto.SaveRequest save() {
        return new ScenarioDto.SaveRequest("바꿈", List.of(
                new ScenarioDto.PointRequest(BigDecimal.valueOf(37.51), BigDecimal.valueOf(127.01)),
                new ScenarioDto.PointRequest(BigDecimal.valueOf(37.56), BigDecimal.valueOf(127.06))), StructureType.ELEVATED);
    }

    @Test
    @DisplayName("수정은 시나리오 행을 먼저 잠그고, 그다음에 읽는다 — 같은 시나리오 동시 수정이 500 이 되지 않게")
    void updateLocksTheRowBeforeReading() {
        service.update(OWNER, SCENARIO, save());

        var order = Mockito.inOrder(scenarios);
        order.verify(scenarios).lockOwned(SCENARIO, OWNER);
        order.verify(scenarios).findWithDetailsById(SCENARIO);
    }

    @Test
    @DisplayName("삭제도 시나리오 행을 먼저 잠근다 — 계산·수정 중인 시나리오를 지울 때 교착이 나지 않게")
    void deleteLocksTheRowBeforeReading() {
        service.delete(OWNER, SCENARIO);

        var order = Mockito.inOrder(scenarios);
        order.verify(scenarios).lockOwned(SCENARIO, OWNER);
        order.verify(scenarios).findWithDetailsById(SCENARIO);
    }

    /**
     * `FOR UPDATE` 는 트랜잭션 안에서만 뜻이 있다. 이 테스트는 서비스를 프록시 없이 직접 만들어 `@Transactional` 이 적용되지 않으므로(계산은 `TransactionTemplate` 으로
     * 직접 열어 따로 고정한다) **두 메서드에 애너테이션이 붙어 있는지**로 고정한다 — 떼면 잠금이 트랜잭션 밖에서 불려 줄 세우기가 사라진다.
     */
    @Test
    @DisplayName("수정·삭제는 @Transactional 이다 — 잠금이 트랜잭션 안에서 불린다")
    void updateAndDeleteRunInATransaction() throws NoSuchMethodException {
        assertThat(ScenarioService.class.getMethod("update", Long.class, Long.class, ScenarioDto.SaveRequest.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
        assertThat(ScenarioService.class.getMethod("delete", Long.class, Long.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    @Test
    @DisplayName("남의 시나리오는 잠그지도 읽지도 않고 404 다 — 소유자 조건이 잠금 조회에 들어 있다")
    void strangerGetsNotFoundWithoutReading() {
        Mockito.when(scenarios.lockOwned(SCENARIO, STRANGER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(STRANGER, SCENARIO, save()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.delete(STRANGER, SCENARIO))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        Mockito.verify(scenarios, Mockito.never()).findWithDetailsById(Mockito.anyLong());
    }
}
