package com.railfeas.reference;

import com.railfeas.common.CostStatus;
import com.railfeas.common.ModeType;
import com.railfeas.common.RailClass;
import com.railfeas.common.RegionClass;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실제 노선 사례. ETL(etl/etl.py)이 만든 S2 시드로만 채워지고 앱에서는 읽기만 한다.
 * cost_per_km / avg_spacing_km / data_grade 는 DB 생성 컬럼이라 쓰기 금지.
 */
@Entity
@Table(name = "reference_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReferenceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "line_id")
    private Long id;

    @Column(name = "line_name", nullable = false)
    private String lineName;

    @Column(name = "section_name")
    private String sectionName;

    private String operator;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail_class", nullable = false)
    private RailClass railClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_type")
    private ModeType modeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "region_class", nullable = false)
    private RegionClass regionClass;

    @Column(name = "length_km")
    private BigDecimal lengthKm;

    @Column(name = "station_count")
    private Integer stationCount;

    @Column(name = "underground_ratio")
    private BigDecimal undergroundRatio;

    @Column(name = "total_cost")
    private Long totalCost;

    @Column(name = "base_year")
    private Short baseYear;

    @Column(name = "total_cost_2025")
    private Long totalCost2025;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_status", nullable = false)
    private CostStatus costStatus;

    @Column(name = "opened_year")
    private Short openedYear;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(name = "is_outlier", nullable = false)
    private Boolean outlier;

    private String note;

    // --- DB 생성 컬럼 (읽기 전용) ---

    @Column(name = "cost_per_km", insertable = false, updatable = false)
    private BigDecimal costPerKm;

    @Column(name = "avg_spacing_km", insertable = false, updatable = false)
    private BigDecimal avgSpacingKm;

    @Column(name = "data_grade", insertable = false, updatable = false)
    private String dataGrade;
}
