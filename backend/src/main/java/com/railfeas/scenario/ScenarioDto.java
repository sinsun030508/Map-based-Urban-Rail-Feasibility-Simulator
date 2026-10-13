package com.railfeas.scenario;

import com.railfeas.calc.ExistingOptions;
import com.railfeas.common.ModeType;
import com.railfeas.common.PointType;
import com.railfeas.common.RegionClass;
import com.railfeas.common.StructureType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ScenarioDto {

    /** 한반도 범위를 벗어난 좌표는 받지 않는다 */
    public record PointRequest(
            @NotNull @DecimalMin("33.0") @DecimalMax("39.0") BigDecimal latitude,
            @NotNull @DecimalMin("124.0") @DecimalMax("132.0") BigDecimal longitude) {
    }

    public record SaveRequest(
            @NotBlank @Size(max = 100) String title,
            @NotNull @Size(min = 2, max = 50) List<@Valid PointRequest> points,
            StructureType preferredStructure) {
    }

    public record PointResponse(int sequence, BigDecimal latitude, BigDecimal longitude,
                                PointType pointType) {
        static PointResponse of(RoutePoint p) {
            return new PointResponse(p.getSequence(), p.getLatitude(), p.getLongitude(),
                    p.getPointType());
        }
    }

    public record StationResponse(int sequence, String name, BigDecimal latitude,
                                  BigDecimal longitude, Integer estimatedDailyUsers,
                                  boolean recommended) {
        static StationResponse of(Station s) {
            return new StationResponse(s.getSequence(), s.getName(), s.getLatitude(),
                    s.getLongitude(), s.getEstimatedDailyUsers(),
                    Boolean.TRUE.equals(s.getRecommended()));
        }
    }

    public record ResultResponse(ModeType modeType, StructureType structureType,
                                 int stationCount, long totalCost, Integer estimatedRidership,
                                 Integer peakPphpd, BigDecimal travelTimeMin, Long benefitTotal,
                                 BigDecimal bcRatio, boolean feasible, String warning,
                                 List<StationResponse> stations) {
        static ResultResponse of(ScenarioResult r) {
            return new ResultResponse(r.getModeType(), r.getStructureType(), r.getStationCount(),
                    r.getTotalCost(), r.getEstimatedRidership(), r.getPeakPphpd(),
                    r.getTravelTimeMin(), r.getBenefitTotal(), r.getBcRatio(),
                    Boolean.TRUE.equals(r.getFeasible()), r.getWarning(),
                    r.getStations().stream().map(StationResponse::of).toList());
        }
    }

    public record SummaryResponse(Long id, String title, BigDecimal totalLengthKm,
                                  RegionClass regionClass, ModeType recommendedMode,
                                  StructureType preferredStructure, LocalDateTime createdAt,
                                  LocalDateTime calculatedAt) {
        static SummaryResponse of(Scenario s) {
            return new SummaryResponse(s.getId(), s.getTitle(), s.getTotalLengthKm(),
                    s.getRegionClass(), s.getRecommendedMode(), s.getPreferredStructure(),
                    s.getCreatedAt(), s.getCalculatedAt());
        }
    }

    /**
     * `existingOption` 은 **저장된 값이 아니라 응답할 때 다시 찾은 값**이다.
     *
     * `scenario.existing_line` 에는 계산 시점의 문구만 150자로 남는데, 비교표에 한 줄로
     * 그리려면 짧은 이름·설명이 따로 필요하다. 그 구조를 컬럼으로 늘리려면 스키마를 고쳐야
     * 하고 그러면 `down -v` 가 필요해 **가입 계정과 시나리오가 사라진다.** 판정은 노선
     * 좌표와 역 좌표 CSV 만으로 정해지는 순수 함수이고 역이 1,124개뿐이라 매번 다시 찾는
     * 편이 싸다 — 게다가 CSV 가 갱신되면 답도 같이 갱신된다.
     */
    public record DetailResponse(Long id, String title, BigDecimal totalLengthKm,
                                 RegionClass regionClass, Long population1km,
                                 ModeType recommendedMode, StructureType preferredStructure,
                                 ExistingOptions.Option existingOption, LocalDateTime createdAt,
                                 LocalDateTime calculatedAt,
                                 List<PointResponse> points, List<ResultResponse> results) {
        static DetailResponse of(Scenario s, ExistingOptions.Option existingOption) {
            return new DetailResponse(s.getId(), s.getTitle(), s.getTotalLengthKm(),
                    s.getRegionClass(), s.getPopulation1km(), s.getRecommendedMode(),
                    s.getPreferredStructure(), existingOption, s.getCreatedAt(),
                    s.getCalculatedAt(),
                    s.getPoints().stream().map(PointResponse::of).toList(),
                    s.getResults().stream().map(ResultResponse::of).toList());
        }
    }
}
