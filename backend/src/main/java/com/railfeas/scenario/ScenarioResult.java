package com.railfeas.scenario;

import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 같은 노선을 수단·구조 조합별로 계산한 결과 한 줄. 비교표의 한 행에 해당한다. */
@Entity
@Table(name = "scenario_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScenarioResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id")
    private Scenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_type", nullable = false)
    private ModeType modeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "structure_type", nullable = false)
    private StructureType structureType;

    @Column(name = "standard_id")
    private Long standardId;

    @Column(name = "station_count", nullable = false)
    private Integer stationCount;

    @Column(name = "total_cost", nullable = false)
    private Long totalCost;

    @Column(name = "estimated_ridership")
    private Integer estimatedRidership;

    @Column(name = "peak_pphpd")
    private Integer peakPphpd;

    @Column(name = "travel_time_min")
    private BigDecimal travelTimeMin;

    @Column(name = "benefit_total")
    private Long benefitTotal;

    @Column(name = "bc_ratio")
    private BigDecimal bcRatio;

    @Column(name = "is_feasible", nullable = false)
    private Boolean feasible;

    private String warning;

    @OneToMany(mappedBy = "result", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence asc")
    private List<Station> stations = new ArrayList<>();

    @Builder
    private ScenarioResult(Scenario scenario, ModeType modeType, StructureType structureType,
                           Long standardId, Integer stationCount, Long totalCost,
                           Integer estimatedRidership, Integer peakPphpd, BigDecimal travelTimeMin,
                           Long benefitTotal, BigDecimal bcRatio, Boolean feasible, String warning) {
        this.scenario = scenario;
        this.modeType = modeType;
        this.structureType = structureType;
        this.standardId = standardId;
        this.stationCount = stationCount;
        this.totalCost = totalCost;
        this.estimatedRidership = estimatedRidership;
        this.peakPphpd = peakPphpd;
        this.travelTimeMin = travelTimeMin;
        this.benefitTotal = benefitTotal;
        this.bcRatio = bcRatio;
        this.feasible = feasible == null || feasible;
        this.warning = warning;
    }
}
