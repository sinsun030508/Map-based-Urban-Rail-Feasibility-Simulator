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

    /** total_cost 중 산 구간 터널비(억원). 0 = 판정했고 더할 것 없음, NULL = 모른다(트램·BRT·지형 자료 없음) */
    @Column(name = "tunnel_cost")
    private Long tunnelCost;

    /**
     * total_cost 중 트램·BRT 의 새 도로 공사비(억원) — 기존 도로로 못 가 새로 놓은 구간이 강·호수를 건너는 다리값(11-12). 땅 위 새 도로
     * 공사비는 KDI 에 km 표준이 없어 빠진다(경고에 적는다). 0 = 새 다리 없음, NULL = 철도 수단이거나 도로망 자료 없음
     */
    @Column(name = "road_cost")
    private Long roadCost;

    /** 트램·BRT 가 따라간 노선 — `RoadRoute.encode`(도로 R / 새 도로 N 구간). 철도 수단·도로망 없음은 NULL */
    @Column(name = "route_line", columnDefinition = "mediumtext")
    private String routeLine;

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
                           Long standardId, Integer stationCount, Long totalCost, Long tunnelCost,
                           Long roadCost, String routeLine,
                           Integer estimatedRidership, Integer peakPphpd, BigDecimal travelTimeMin,
                           Long benefitTotal, BigDecimal bcRatio, Boolean feasible, String warning) {
        this.scenario = scenario;
        this.modeType = modeType;
        this.structureType = structureType;
        this.standardId = standardId;
        this.stationCount = stationCount;
        this.totalCost = totalCost;
        this.tunnelCost = tunnelCost;
        this.roadCost = roadCost;
        this.routeLine = routeLine;
        this.estimatedRidership = estimatedRidership;
        this.peakPphpd = peakPphpd;
        this.travelTimeMin = travelTimeMin;
        this.benefitTotal = benefitTotal;
        this.bcRatio = bcRatio;
        this.feasible = feasible == null || feasible;
        this.warning = warning;
    }

    /** 배치된 정차역을 붙인다. 결과가 지워지면 cascade 로 함께 지워진다 */
    public void addStation(Station station) {
        this.stations.add(station);
    }
}
