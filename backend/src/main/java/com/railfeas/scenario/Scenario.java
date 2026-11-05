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

    /** 마지막 계산 시각. 기준값이 이 뒤에 바뀌었으면 저장된 결과는 낡은 것이다 */
    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt;

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

    /**
     * 좌표를 고치기 전에 옛 좌표와 결과를 버린다.
     *
     * `uk_point_seq(scenario_id, sequence)` 때문에 {@link #clearResults()} 와 같은 함정이 있다 —
     * 한 flush 에 맡기면 새 점 `sequence 0` INSERT 가 옛 점 DELETE 보다 먼저 나가 1062 로
     * 실패한다. 호출부가 이 메서드 뒤에 flush 를 끼운다 (`ScenarioService.update`).
     * 실제로 저장된 시나리오를 고쳐 덮어쓰면 **항상** 500 이 났다.
     */
    public void clearPoints() {
        this.points.clear();
        forgetCalculation();
    }

    public void replacePoints(List<RoutePoint> newPoints, BigDecimal totalLengthKm) {
        this.points.clear();
        newPoints.forEach(p -> {
            p.assignTo(this);
            this.points.add(p);
        });
        this.totalLengthKm = totalLengthKm;
        forgetCalculation();
    }

    /**
     * 좌표가 바뀌면 옛 노선으로 낸 값은 **모두** 맞지 않는다 — 결과만이 아니다.
     *
     * 전에는 결과·추천만 지워 반경 1km 인구와 계산 시각이 옛 노선 값으로 남았다. 노선을 고쳐 저장하면
     * 시나리오 비교의 「주변 인구」 칸에 **옛 노선의 인구**가 새 노선 이름 옆에 찍혔다(11-05).
     * 0 이 아니라 null 로 돌린다 — 「인구 0」 이 아니라 「아직 모름」 이다. 계산 시각도 비워야
     * 「다시 계산 필요」 가 옛 계산을 기준으로 판정되지 않는다.
     *
     * 지울 칸이 두 메서드에 따로 복사돼 있어 하나에만 더해지곤 했다 — 여기 한 곳에 둔다.
     */
    private void forgetCalculation() {
        this.results.clear();
        this.recommendedMode = null;
        this.population1km = null;
        this.calculatedAt = null;
        // 기존 노선 안내도 옛 노선의 판정이다. 남겨 두면 재계산 전까지 바뀐 노선에
        // 옛 안내("광명역~오송역")가 붙어 보인다
        this.existingLine = null;
    }

    /**
     * 다시 계산하기 전에 옛 결과를 버린다 — 옛 결과가 새 노선에 붙어 있으면 안 된다.
     *
     * `uk_result_mode(scenario_id, mode_type, structure_type)` 유니크 제약이 있어
     * **옛 행 DELETE 가 새 행 INSERT 보다 먼저 나가야 한다.** Hibernate 는 한 flush 안에서
     * INSERT 를 DELETE 보다 앞에 보내므로, 호출부에서 이 메서드 뒤에 flush 를 끼워
     * 두 단계로 끊어야 한다 (`ScenarioService.calculate`).
     */
    public void clearResults() {
        this.results.clear();
        this.recommendedMode = null;
    }

    /** 겹치는 기존 노선 안내. 없으면 null 로 지운다 */
    public void noteExistingLine(String existingLine) {
        this.existingLine = existingLine;
    }

    /** 계산 결과를 붙인다. 반드시 {@link #clearResults()} + flush 뒤에 호출한다 */
    public void applyResults(List<ScenarioResult> newResults, Long population1km,
                             ModeType recommendedMode) {
        this.results.addAll(newResults);
        this.population1km = population1km;
        this.recommendedMode = recommendedMode;
        // 기준값이 이 시각 뒤에 바뀌면 저장된 결과는 낡은 것이다 (화면에서 알린다)
        this.calculatedAt = LocalDateTime.now();
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
