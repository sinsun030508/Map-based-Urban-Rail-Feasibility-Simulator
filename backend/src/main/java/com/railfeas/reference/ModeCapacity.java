package com.railfeas.reference;

import com.railfeas.common.ModeType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

/** 수단 추천의 제약조건. 값은 db/seed/S1__mode_capacity.sql 이 넣는다. */
@Entity
@Table(name = "mode_capacity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModeCapacity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "mode_type")
    private ModeType modeType;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "pphpd_min")
    private Integer pphpdMin;

    @Column(name = "pphpd_max")
    private Integer pphpdMax;

    @Column(name = "cost_per_km_min")
    private Integer costPerKmMin;

    @Column(name = "cost_per_km_max")
    private Integer costPerKmMax;

    @Column(name = "spacing_km")
    private BigDecimal spacingKm;

    /** 표정속도 — 편익 계산용. 현재 값은 실측이 아니라 가정값이다 */
    @Column(name = "speed_kmh")
    private BigDecimal speedKmh;

    /** 권장 최대 연장. 넘으면 복선전철을 후보에 추가하고 이 수단은 경고만 표시 */
    @Column(name = "max_length_km")
    private BigDecimal maxLengthKm;

    private String source;
}
