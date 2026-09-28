package com.railfeas.scenario;

import com.railfeas.common.PointType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "route_point")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "point_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id")
    private Scenario scenario;

    @Column(nullable = false)
    private Integer sequence;

    @Column(nullable = false)
    private BigDecimal latitude;

    @Column(nullable = false)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "point_type", nullable = false)
    private PointType pointType;

    @Builder
    private RoutePoint(Integer sequence, BigDecimal latitude, BigDecimal longitude,
                       PointType pointType) {
        this.sequence = sequence;
        this.latitude = latitude;
        this.longitude = longitude;
        this.pointType = pointType;
    }

    void assignTo(Scenario scenario) {
        this.scenario = scenario;
    }
}
