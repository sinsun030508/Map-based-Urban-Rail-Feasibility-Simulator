package com.railfeas.scenario;

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

    public record DetailResponse(Long id, String title, BigDecimal totalLengthKm,
                                 RegionClass regionClass, Long population1km,
                                 ModeType recommendedMode, StructureType preferredStructure,
                                 String existingLine, LocalDateTime createdAt,
                                 LocalDateTime calculatedAt,
                                 List<PointResponse> points, List<ResultResponse> results) {
        static DetailResponse of(Scenario s) {
            return new DetailResponse(s.getId(), s.getTitle(), s.getTotalLengthKm(),
                    s.getRegionClass(), s.getPopulation1km(), s.getRecommendedMode(),
                    s.getPreferredStructure(), s.getExistingLine(), s.getCreatedAt(),
                    s.getCalculatedAt(),
                    s.getPoints().stream().map(PointResponse::of).toList(),
                    s.getResults().stream().map(ResultResponse::of).toList());
        }
    }
}
