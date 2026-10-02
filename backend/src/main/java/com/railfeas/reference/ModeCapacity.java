package com.railfeas.reference;

import com.railfeas.common.ModeType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    /** 표정속도 — 편익 계산용. BRT 2종만 가정값이고 나머지는 실측이다 (S1 주석 참고) */
    @Column(name = "speed_kmh")
    private BigDecimal speedKmh;

    /** 권장 최대 연장. 넘으면 복선전철을 후보에 추가하고 이 수단은 경고만 표시 */
    @Column(name = "max_length_km")
    private BigDecimal maxLengthKm;

    private String source;

    /** 관리자가 고친 시각 — 저장된 계산 결과가 낡았는지 판단한다 */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /**
     * 관리자 페이지에서 기준값을 고친다. null 은 "안 바꿈"이 아니라 **지운다**는 뜻이다 —
     * `pphpd_min` 은 근거가 없을 때 NULL 로 두는 것이 정상이기 때문이다(S1 주석).
     * 근거(`source`)는 반드시 함께 남긴다.
     */
    public void changeBounds(Integer pphpdMin, Integer pphpdMax, BigDecimal spacingKm,
                             BigDecimal speedKmh, BigDecimal maxLengthKm, String source) {
        this.pphpdMin = pphpdMin;
        this.pphpdMax = pphpdMax;
        this.spacingKm = spacingKm;
        this.speedKmh = speedKmh;
        this.maxLengthKm = maxLengthKm;
        this.source = source;
    }
}
