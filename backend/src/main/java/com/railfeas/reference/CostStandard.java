package com.railfeas.reference;

import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 총사업비 = fixedCost + 연장 x costPerKm + 역수 x costPerStation */
@Entity
@Table(name = "cost_standard")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CostStandard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "standard_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_type", nullable = false)
    private ModeType modeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "structure_type", nullable = false)
    private StructureType structureType;

    @Column(name = "fixed_cost", nullable = false)
    private Long fixedCost;

    @Column(name = "cost_per_km", nullable = false)
    private Long costPerKm;

    @Column(name = "cost_per_station", nullable = false)
    private Long costPerStation;

    @Column(name = "base_year")
    private Short baseYear;

    /** 시드는 DB 기본값(오늘)으로, 관리자 수정은 {@link #nextVersion} 이 채운다 */
    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    private String source;

    /** 관리자가 고친 다음 기준. 이 행은 건드리지 않는다 — 저장된 결과가 참조한다 */
    public CostStandard nextVersion(long fixedCost, long costPerKm, long costPerStation, String source) {
        CostStandard next = new CostStandard();
        next.modeType = modeType;
        next.structureType = structureType;
        next.fixedCost = fixedCost;
        next.costPerKm = costPerKm;
        next.costPerStation = costPerStation;
        next.baseYear = baseYear;
        next.effectiveFrom = LocalDate.now();
        next.active = true;
        next.source = source;
        return next;
    }
}
