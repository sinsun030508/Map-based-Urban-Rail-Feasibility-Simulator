package com.railfeas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.common.ApiException;
import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.CostStandardRepository;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 단가 수정은 **덮어쓰지 않는다** — 저장된 결과가 `standard_id` 로 그 행을 참조해, 덮어쓰면 과거 B/C 의 근거와
 * 비용 구성이 함께 바뀐다. 새 행을 만들고 이전 행을 내린다(스키마 주석과 S5 시드가 같은 규칙이다).
 */
class AdminCostControllerTest {

    private final CostStandardRepository repo = Mockito.mock(CostStandardRepository.class);
    private final AdminCostController controller = new AdminCostController(repo);

    private static CostStandard row(long id, ModeType mode, StructureType structure, long perKm, boolean active) {
        try {
            Constructor<CostStandard> c = CostStandard.class.getDeclaredConstructor();
            c.setAccessible(true);
            CostStandard s = c.newInstance();
            ReflectionTestUtils.setField(s, "id", id);
            ReflectionTestUtils.setField(s, "modeType", mode);
            ReflectionTestUtils.setField(s, "structureType", structure);
            ReflectionTestUtils.setField(s, "fixedCost", 1_000L);
            ReflectionTestUtils.setField(s, "costPerKm", perKm);
            ReflectionTestUtils.setField(s, "costPerStation", 200L);
            ReflectionTestUtils.setField(s, "baseYear", (short) 2025);
            ReflectionTestUtils.setField(s, "active", active);
            ReflectionTestUtils.setField(s, "source", "시드");
            return s;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 중전철 지하 875(id 1)·고가 550(id 2) */
    private CostStandard[] stored() {
        CostStandard under = row(1, ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 875, true);
        CostStandard elevated = row(2, ModeType.HEAVY_METRO, StructureType.ELEVATED, 550, true);
        Mockito.when(repo.findById(1L)).thenReturn(Optional.of(under));
        Mockito.when(repo.findById(2L)).thenReturn(Optional.of(elevated));
        Mockito.when(repo.findByActiveTrue()).thenReturn(List.of(under, elevated));
        Mockito.when(repo.deactivate(Mockito.anyLong())).thenReturn(1);
        Mockito.when(repo.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        return new CostStandard[]{under, elevated};
    }

    private static AdminCostController.CostRequest request(long fixed, long perKm, long perStation) {
        return new AdminCostController.CostRequest(fixed, perKm, perStation, "2026 재추정");
    }

    private HttpStatus statusOf(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e.getStatus();
        }
        throw new AssertionError("막혀야 한다");
    }

    private void nothingWritten() {
        Mockito.verify(repo, Mockito.never()).deactivate(Mockito.anyLong());
        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("수정은 새 행을 만들고 이전 행을 내린다 — 이전 행의 값은 그대로다")
    void 새_행을_만든다() {
        CostStandard old = stored()[1];

        var row = controller.update(2L, request(1_100, 600, 210));

        Mockito.verify(repo).deactivate(2L);
        ArgumentCaptor<CostStandard> saved = ArgumentCaptor.forClass(CostStandard.class);
        Mockito.verify(repo).save(saved.capture());
        CostStandard next = saved.getValue();
        assertThat(next).isNotSameAs(old);
        assertThat(next.getId()).isNull();
        assertThat(next.getModeType()).isEqualTo(ModeType.HEAVY_METRO);
        assertThat(next.getStructureType()).isEqualTo(StructureType.ELEVATED);
        assertThat(next.getFixedCost()).isEqualTo(1_100);
        assertThat(next.getCostPerKm()).isEqualTo(600);
        assertThat(next.getCostPerStation()).isEqualTo(210);
        assertThat(next.getBaseYear()).isEqualTo((short) 2025);
        assertThat(next.getSource()).isEqualTo("2026 재추정");
        assertThat(next.getActive()).isTrue();
        assertThat(next.getEffectiveFrom()).isNotNull();
        assertThat(old.getCostPerKm()).isEqualTo(550);
        assertThat(row.active()).isTrue();
    }

    @Test
    @DisplayName("이미 내려간 행은 고칠 수 없다")
    void 내려간_행() {
        Mockito.when(repo.findById(5L)).thenReturn(Optional.of(row(5, ModeType.TRAM, StructureType.AT_GRADE, 273, false)));

        assertThat(statusOf(() -> controller.update(5L, request(1, 2, 3)))).isEqualTo(HttpStatus.CONFLICT);
        nothingWritten();
    }

    @Test
    @DisplayName("두 관리자가 같은 행을 고치면 뒤쪽은 409 — 활성 행이 둘이 되면 계산이 깨진다")
    void 동시_수정() {
        stored();
        Mockito.when(repo.deactivate(2L)).thenReturn(0);

        assertThat(statusOf(() -> controller.update(2L, request(1_100, 600, 210)))).isEqualTo(HttpStatus.CONFLICT);
        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("없는 행은 404")
    void 없는_행() {
        Mockito.when(repo.findById(9L)).thenReturn(Optional.empty());

        assertThat(statusOf(() -> controller.update(9L, request(1, 2, 3)))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("km단가는 1 이상 — 0 이면 손익분기가 0 으로 나뉜다")
    void km단가() {
        stored();

        assertThat(statusOf(() -> controller.update(2L, request(1_000, 0, 200)))).isEqualTo(HttpStatus.BAD_REQUEST);
        nothingWritten();
    }

    @Test
    @DisplayName("고정비·역 단가는 음수일 수 없다 — 0 은 된다(BRT 저급형처럼 작은 값이 있다)")
    void 음수() {
        stored();

        assertThat(statusOf(() -> controller.update(2L, request(-1, 550, 200)))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> controller.update(2L, request(1_000, 550, -1)))).isEqualTo(HttpStatus.BAD_REQUEST);
        nothingWritten();
        assertThat(controller.update(2L, request(0, 550, 0)).fixedCost()).isZero();
    }

    @Test
    @DisplayName("값도 근거도 그대로면 새 행을 만들지 않는다")
    void 그대로면_막는다() {
        stored();

        assertThat(statusOf(() -> controller.update(2L,
                new AdminCostController.CostRequest(1_000L, 550L, 200L, "시드")))).isEqualTo(HttpStatus.BAD_REQUEST);
        nothingWritten();
    }

    @Test
    @DisplayName("근거만 고쳐도 된다 — 이 프로젝트에서 값보다 중요한 것이 출처다")
    void 근거만() {
        stored();

        var row = controller.update(2L, new AdminCostController.CostRequest(1_000L, 550L, 200L, "출처 보강"));

        assertThat(row.source()).isEqualTo("출처 보강");
    }

    @Test
    @DisplayName("지하 km단가가 같은 수단의 고가보다 낮으면 막는다 — 산 구간의 뚫기가 음수 비용이 된다")
    void 지하가_더_싸면_막는다() {
        stored();

        assertThat(statusOf(() -> controller.update(2L, request(1_000, 900, 200)))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> controller.update(1L, request(1_000, 500, 200)))).isEqualTo(HttpStatus.BAD_REQUEST);
        nothingWritten();
    }

    @Test
    @DisplayName("목록은 활성 행이 먼저, 이전 기준은 최근 것부터")
    void 목록() {
        Mockito.when(repo.findAll()).thenReturn(new ArrayList<>(List.of(
                row(1, ModeType.HEAVY_METRO, StructureType.ELEVATED, 500, false),
                row(3, ModeType.HEAVY_METRO, StructureType.ELEVATED, 520, false),
                row(7, ModeType.HEAVY_METRO, StructureType.ELEVATED, 550, true))));

        assertThat(controller.costs()).extracting(AdminCostController.CostRow::id).containsExactly(7L, 3L, 1L);
    }
}
