package com.railfeas.reference;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 시간가치 등 편익 원단위. 관리자 페이지에서 수정한다. */
@Entity
@Table(name = "benefit_parameter")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenefitParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "param_id")
    private Long id;

    @Column(name = "param_name", nullable = false, unique = true)
    private String paramName;

    @Column(nullable = false)
    private BigDecimal value;

    private String unit;

    private String source;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public void changeValue(BigDecimal value, String source) {
        this.value = value;
        this.source = source;
    }
}
