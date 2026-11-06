package com.railfeas.admin;

import com.railfeas.common.ApiException;
import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.CostStandardRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 건설비 단가 관리 (제안서 기능 8).
 *
 * <p><b>수정은 덮어쓰지 않는다 — 새 행을 만들고 이전 행을 내린다.</b> 저장된 결과가 `standard_id` 로 그 행을 참조해,
 * 덮어쓰면 과거 B/C 의 근거와 비용 구성이 함께 바뀐다. 스키마 주석과 S5 시드가 같은 규칙이다. 그 단가로 계산한
 * 결과에는 화면이 「다시 계산」 을 띄운다(`ScenarioDto.standardChanged`).
 *
 * <p>추가·삭제는 없다 — 다른 기준값과 같다. 행은 `etl/cost_model.py` 가 만들고, 외래키가 삭제를 막는다.
 * <b>값은 회귀 결과라</b> 손으로 고치면 모델과 어긋나고 시드를 다시 넣으면 내려간다.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "관리자 — 단가")
@RestController
@RequestMapping("/api/admin/costs")
public class AdminCostController {

    private final CostStandardRepository standards;

    public AdminCostController(CostStandardRepository standards) {
        this.standards = standards;
    }

    public record CostRow(Long id, ModeType modeType, StructureType structureType, long fixedCost, long costPerKm,
                          long costPerStation, Short baseYear, LocalDate effectiveFrom, boolean active, String source) {
        static CostRow of(CostStandard s) {
            return new CostRow(s.getId(), s.getModeType(), s.getStructureType(), s.getFixedCost(), s.getCostPerKm(),
                    s.getCostPerStation(), s.getBaseYear(), s.getEffectiveFrom(), Boolean.TRUE.equals(s.getActive()),
                    s.getSource());
        }
    }

    public record CostRequest(@NotNull Long fixedCost, @NotNull Long costPerKm, @NotNull Long costPerStation,
                              @NotBlank @Size(max = 150) String source) {
    }

    /** 활성 행이 먼저(수단·구조 순), 이전 기준은 최근 것부터 */
    @GetMapping
    public List<CostRow> costs() {
        return standards.findAll().stream()
                .sorted(Comparator.comparing((CostStandard s) -> !Boolean.TRUE.equals(s.getActive()))
                        .thenComparing(CostStandard::getModeType)
                        .thenComparing(CostStandard::getStructureType)
                        .thenComparing(CostStandard::getId, Comparator.reverseOrder()))
                .map(CostRow::of)
                .toList();
    }

    @PutMapping("/{id}")
    @Transactional
    public CostRow update(@PathVariable Long id, @Valid @RequestBody CostRequest request) {
        CostStandard current = standards.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "단가를 찾을 수 없습니다"));
        if (!Boolean.TRUE.equals(current.getActive())) {
            throw stale();
        }
        validate(current, request);
        // 「활성일 때만 내린다」 를 쿼리 한 번으로 — 두 관리자가 같은 행을 고치면 활성 행이 둘이 되어 결과의
        // (시나리오, 수단, 구조) 유일 제약에 걸린다. 뒤쪽은 내린 행이 0 개라 여기서 멈춘다
        if (standards.deactivate(id) == 0) {
            throw stale();
        }
        return CostRow.of(standards.save(current.nextVersion(request.fixedCost(), request.costPerKm(),
                request.costPerStation(), request.source())));
    }

    private void validate(CostStandard current, CostRequest r) {
        // km단가 0 이면 산 구간의 손익분기가 0 으로 나뉜다
        if (r.costPerKm() < 1) {
            throw bad("km단가는 1 이상이어야 합니다");
        }
        if (r.fixedCost() < 0 || r.costPerStation() < 0) {
            throw bad("고정비와 역 단가는 음수일 수 없습니다");
        }
        if (r.fixedCost().equals(current.getFixedCost()) && r.costPerKm().equals(current.getCostPerKm())
                && r.costPerStation().equals(current.getCostPerStation()) && r.source().equals(current.getSource())) {
            throw bad("바뀐 값이 없습니다");
        }
        // 지하가 고가보다 싸면 산 구간의 「뚫기」 가 음수 비용이 된다
        boolean underground = current.getStructureType() == StructureType.UNDERGROUND;
        for (CostStandard other : standards.findByActiveTrue()) {
            if (other.getModeType() != current.getModeType() || other.getStructureType() == current.getStructureType()) {
                continue;
            }
            long under = underground ? r.costPerKm() : other.getCostPerKm();
            long above = underground ? other.getCostPerKm() : r.costPerKm();
            boolean pair = underground || other.getStructureType() == StructureType.UNDERGROUND;
            if (pair && under < above) {
                throw bad("지하 km단가(" + under + ")가 같은 수단의 지상·고가(" + above + ")보다 낮을 수 없습니다");
            }
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static ApiException stale() {
        return new ApiException(HttpStatus.CONFLICT, "이미 새 기준으로 바뀐 단가입니다 — 목록을 다시 불러와 주세요");
    }
}
