package com.railfeas.reference;

import com.railfeas.common.Severity;
import com.railfeas.common.ZoneType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 좌표·반경이 근사치라 통과 가부를 단정하지 말고 "확인 필요"로만 안내한다. */
@Entity
@Table(name = "restricted_zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestrictedZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "zone_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone_type", nullable = false)
    private ZoneType zoneType;

    @Column(nullable = false)
    private String name;

    private String region;

    @Column(name = "center_lat", nullable = false)
    private BigDecimal centerLat;

    @Column(name = "center_lng", nullable = false)
    private BigDecimal centerLng;

    @Column(name = "radius_km", nullable = false)
    private BigDecimal radiusKm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    private String note;

    private String source;
}
