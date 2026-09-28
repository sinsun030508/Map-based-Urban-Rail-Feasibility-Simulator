package com.railfeas.scenario;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 정차역은 역간격이 수단마다 달라 시나리오가 아니라 계산 결과에 매달린다. */
@Entity
@Table(name = "station")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Station {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "station_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "result_id")
    private ScenarioResult result;

    @Column(nullable = false)
    private Integer sequence;

    private String name;

    @Column(nullable = false)
    private BigDecimal latitude;

    @Column(nullable = false)
    private BigDecimal longitude;

    @Column(name = "estimated_daily_users")
    private Integer estimatedDailyUsers;

    @Column(name = "is_recommended", nullable = false)
    private Boolean recommended;

    @Builder
    private Station(ScenarioResult result, Integer sequence, String name,
                    BigDecimal latitude, BigDecimal longitude,
                    Integer estimatedDailyUsers, Boolean recommended) {
        this.result = result;
        this.sequence = sequence;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.estimatedDailyUsers = estimatedDailyUsers;
        this.recommended = recommended == null || recommended;
    }
}
