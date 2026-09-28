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

    @Column(name = "effective_from", nullable = false, insertable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    private String source;
}
