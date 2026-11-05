package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.railfeas.calc.CostCalculator;
import com.railfeas.calc.ExistingOptions;
import com.railfeas.common.ModeType;
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
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * **제목만 고쳐 저장하면 계산 결과가 남아야 한다.**
 *
 * 화면은 「수정 저장」 때 점을 늘 함께 보내는데, `update` 가 점이 바뀌었는지 보지 않고 결과를
 * 지워서 이름 하나 바꾸면 계산이 통째로 사라졌다(11-05). 점이 실제로 바뀔 때만 지운다.
 */
class ScenarioUpdateTest {

    private static final long OWNER = 7L;
    private static final long SCENARIO = 1L;

    private final ScenarioRepository scenarios = mock(ScenarioRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final ScenarioService service = new ScenarioService(scenarios, mock(UserRepository.class),
            mock(CostStandardRepository.class), mock(ModeCapacityRepository.class),
            mock(RestrictedZoneRepository.class), mock(CostCalculator.class),
            mock(ExistingOptions.class), entityManager);

    private static User owner() {
        User u = User.builder().email("a@example.test").nickname("n")
                .provider(AuthProvider.LOCAL).role(Role.USER).build();
        try {
            Field id = User.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(u, OWNER);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return u;
    }

    private static RoutePoint stored(int seq, String lat, String lng, PointType type) {
        return RoutePoint.builder().sequence(seq).pointType(type)
                .latitude(new BigDecimal(lat)).longitude(new BigDecimal(lng)).build();
    }

    /** DB 에서 읽은 것처럼 좌표가 소수 7자리인, 계산까지 마친 시나리오 */
    private Scenario calculatedScenario() {
        Scenario s = Scenario.builder().user(owner()).title("시청~사당")
                .totalLengthKm(new BigDecimal("10.000"))
                .preferredStructure(StructureType.UNDERGROUND).build();
        s.replacePoints(List.of(
                stored(0, "37.5665123", "126.9780000", PointType.START),
                stored(1, "37.4765000", "126.9815432", PointType.END)), new BigDecimal("10.000"));
        s.applyResults(List.of(ScenarioResult.builder().scenario(s)
                .modeType(ModeType.LIGHT_RAIL).structureType(StructureType.ELEVATED)
                .stationCount(2).totalCost(100L).build()), 123_456L, ModeType.LIGHT_RAIL);
        when(scenarios.findWithDetailsById(SCENARIO)).thenReturn(Optional.of(s));
        return s;
    }

    private static ScenarioDto.PointRequest p(String lat, String lng) {
        return new ScenarioDto.PointRequest(new BigDecimal(lat), new BigDecimal(lng));
    }

    @Test
    @DisplayName("제목·구조만 바꾸면 결과·인구·계산 시각이 남는다")
    void renamingKeepsTheCalculation() {
        Scenario s = calculatedScenario();
        var calculatedAt = s.getCalculatedAt();

        // 화면이 보내는 모양 — 클릭한 그대로의 긴 소수, 끝의 0 을 뗀 값
        var detail = service.update(OWNER, SCENARIO, new ScenarioDto.SaveRequest("시청~사당 (2안)",
                List.of(p("37.56651234567", "126.978"), p("37.4765", "126.98154321")),
                StructureType.ELEVATED));

        assertThat(detail.title()).isEqualTo("시청~사당 (2안)");
        assertThat(detail.preferredStructure()).isEqualTo(StructureType.ELEVATED);
        assertThat(detail.results()).hasSize(1);
        assertThat(detail.population1km()).isEqualTo(123_456L);
        assertThat(detail.calculatedAt()).isEqualTo(calculatedAt);
        assertThat(detail.recommendedMode()).isEqualTo(ModeType.LIGHT_RAIL);
        verify(entityManager, never()).flush();   // 점을 지우고 다시 넣지 않았다
    }

    @Test
    @DisplayName("점을 하나라도 옮기면 옛 결과를 지운다")
    void movingAPointForgetsTheCalculation() {
        calculatedScenario();

        // 도착점을 북쪽으로 약 110m
        var detail = service.update(OWNER, SCENARIO, new ScenarioDto.SaveRequest("시청~사당",
                List.of(p("37.5665123", "126.978"), p("37.4775", "126.9815432")),
                StructureType.UNDERGROUND));

        assertThat(detail.results()).isEmpty();
        assertThat(detail.population1km()).isNull();
        assertThat(detail.calculatedAt()).isNull();
        assertThat(detail.points().get(1).latitude()).isEqualByComparingTo("37.4775");
    }
}
