package com.railfeas.scenario;

import com.railfeas.common.ModeType;
import com.railfeas.common.RegionClass;
import com.railfeas.common.StructureType;
import com.railfeas.user.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "scenario")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "scenario_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(name = "total_length_km", nullable = false)
    private BigDecimal totalLengthKm;

    @Enumerated(EnumType.STRING)
    @Column(name = "region_class")
    private RegionClass regionClass;

    @Column(name = "population_1km")
    private Long population1km;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommended_mode")
    private ModeType recommendedMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_structure")
    private StructureType preferredStructure;

    @Column(name = "existing_line")
    private String existingLine;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence asc")
    private List<RoutePoint> points = new ArrayList<>();

    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ScenarioResult> results = new ArrayList<>();

    @Builder
    private Scenario(User user, String title, BigDecimal totalLengthKm,
                     RegionClass regionClass, StructureType preferredStructure) {
        this.user = user;
        this.title = title;
        this.totalLengthKm = totalLengthKm;
        this.regionClass = regionClass;
        this.preferredStructure = preferredStructure;
    }

    public void replacePoints(List<RoutePoint> newPoints, BigDecimal totalLengthKm) {
        this.points.clear();
        newPoints.forEach(p -> {
            p.assignTo(this);
            this.points.add(p);
        });
        this.totalLengthKm = totalLengthKm;
        // 좌표가 바뀌면 이전 계산 결과는 더 이상 맞지 않는다
        this.results.clear();
        this.recommendedMode = null;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void changeStructure(StructureType structureType) {
        this.preferredStructure = structureType;
    }

    public boolean ownedBy(Long userId) {
        return user.getId().equals(userId);
    }
}
