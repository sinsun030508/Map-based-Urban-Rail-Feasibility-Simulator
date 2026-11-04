package com.railfeas.admin;

import com.railfeas.common.ApiException;
import com.railfeas.common.ModeType;
import com.railfeas.reference.BenefitParameter;
import com.railfeas.reference.BenefitParameterRepository;
import com.railfeas.reference.ModeCapacity;
import com.railfeas.reference.ModeCapacityRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 기준값 CRUD (제안서 기능 8).
 *
 * 비용·편익 원단위와 수단 제약은 대부분 실측·지침값이지만 일부는 아직 가정값이다
 * (BRT 표정속도 등). 코드를 고치지 않고 화면에서 바꿀 수 있어야 한다.
 *
 * **근거(`source`)를 반드시 함께 받는다.** 이 프로젝트에서 값보다 중요한 것이 출처다.
 * 숫자만 바꾸고 출처를 그대로 두면 나중에 그 값이 어디서 왔는지 알 수 없게 된다.
 *
 * 추가·삭제는 없다. 기준값은 ETL·시드가 만드는 고정 목록이고, 행이 사라지면
 * 계산이 바로 멈춘다(`BenefitCalculator.Params` 가 없는 키에 예외를 던진다).
 * 바꿀 수 있는 것은 값과 근거뿐이다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final BenefitParameterRepository parameters;
    private final ModeCapacityRepository modes;

    public AdminController(BenefitParameterRepository parameters, ModeCapacityRepository modes) {
        this.parameters = parameters;
        this.modes = modes;
    }

    public record ParameterResponse(String paramName, BigDecimal value, String unit,
                                    String source, LocalDateTime updatedAt) {
        static ParameterResponse of(BenefitParameter p) {
            return new ParameterResponse(p.getParamName(), p.getValue(), p.getUnit(),
                    p.getSource(), p.getUpdatedAt());
        }
    }

    public record ParameterRequest(@NotNull BigDecimal value, @NotBlank String source) {
    }

    public record ModeResponse(ModeType modeType, String displayName, Integer pphpdMin,
                               Integer pphpdMax, BigDecimal spacingKm, BigDecimal speedKmh,
                               BigDecimal maxLengthKm, String source) {
        static ModeResponse of(ModeCapacity m) {
            return new ModeResponse(m.getModeType(), m.getDisplayName(), m.getPphpdMin(),
                    m.getPphpdMax(), m.getSpacingKm(), m.getSpeedKmh(), m.getMaxLengthKm(),
                    m.getSource());
        }
    }

    /** 비우면 지운다는 뜻이다 — `pphpd_min` 은 근거가 없을 때 NULL 이 정상이다 */
    public record ModeRequest(Integer pphpdMin, Integer pphpdMax, BigDecimal spacingKm,
                              BigDecimal speedKmh, BigDecimal maxLengthKm,
                              @NotBlank String source) {
    }

    @GetMapping("/parameters")
    public List<ParameterResponse> parameters() {
        return parameters.findAll().stream()
                .sorted((a, b) -> a.getParamName().compareTo(b.getParamName()))
                .map(ParameterResponse::of)
                .toList();
    }

    /**
     * 원단위의 허용 범위. 범위를 넘으면 계산이 **조용히 망가지거나 끝나지 않는다.**
     *
     * - 0 이하가 되면 망가지는 값 — 평균 통행거리가 0 이면 모든 편익이 0 이고, 기준 속도가 0 이면
     *   0 으로 나눈다. 화면에는 오류 없이 B/C 0.00 만 줄지어 찍힌다
     * - **기간은 반복 횟수다** — 편익을 해마다 할인해 더하므로 10억 년이면 계산이 끝나지 않는다.
     *   상한(분석 100년·공사 20년)은 판단값이다(지침은 철도 40년·공사 5년 원칙)
     * - 할인율이 음수거나 100% 이상이면 할인이 뜻을 잃는다. 비율은 1 을 넘을 수 없다
     * - **수요 회귀 계수(`demand_*`)는 범위를 두지 않는다** — 절편·기울기는 음수가 정상이다.
     *   운영비 비율처럼 0 이 정상인 값도 있어 일괄 양수를 강요하지 않는다
     */
    record Range(BigDecimal min, boolean minOpen, BigDecimal max, boolean maxOpen) {

        static Range positive() {
            return new Range(BigDecimal.ZERO, true, null, false);
        }

        static Range of(String min, boolean minOpen, String max, boolean maxOpen) {
            return new Range(new BigDecimal(min), minOpen, new BigDecimal(max), maxOpen);
        }

        boolean contains(BigDecimal v) {
            int lo = v.compareTo(min);
            if (lo < 0 || (minOpen && lo == 0)) {
                return false;
            }
            if (max == null) {
                return true;
            }
            int hi = v.compareTo(max);
            return hi < 0 || (!maxOpen && hi == 0);
        }

        @Override
        public String toString() {
            return (minOpen ? min + " 초과" : min + " 이상")
                    + (max == null ? "" : (maxOpen ? " " + max + " 미만" : " " + max + " 이하"));
        }
    }

    static final Map<String, Range> RANGES = Map.ofEntries(
            Map.entry("avg_trip_km", Range.positive()),
            Map.entry("baseline_speed_kmh", Range.positive()),
            Map.entry("time_value_won_per_hour", Range.positive()),
            Map.entry("weekday_factor", Range.positive()),
            Map.entry("peak_direction_coef", Range.positive()),
            Map.entry("analysis_years", Range.of("1", false, "100", false)),
            Map.entry("construction_years", Range.of("1", false, "20", false)),
            Map.entry("discount_switch_year", Range.of("0", false, "100", false)),
            Map.entry("discount_rate", Range.of("0", false, "1", true)),
            Map.entry("discount_rate_late", Range.of("0", false, "1", true)),
            Map.entry("operating_cost_ratio", Range.of("0", false, "1", true)),
            Map.entry("peak_hour_ratio", Range.of("0", true, "1", false)),
            Map.entry("peak_direction_max", Range.of("0", true, "1", false)));

    @PutMapping("/parameters/{name}")
    @Transactional
    public ParameterResponse updateParameter(@PathVariable String name,
                                             @Valid @RequestBody ParameterRequest request) {
        BenefitParameter parameter = parameters.findByParamName(name)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        name + " 기준값이 없습니다"));
        Range range = RANGES.get(name);
        if (range != null && !range.contains(request.value())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, name + " 은 " + range + " 이어야 합니다");
        }
        parameter.changeValue(request.value(), request.source());
        return ParameterResponse.of(parameter);
    }

    /**
     * 역간격의 하한(km). **판단값이다** — 10량 전동차 한 편성이 약 200m 라 그보다 가까운 역은 있을
     * 수 없다. 0·음수면 역 수가 조용히 「종점 2개」 로 바뀌고, 0.0001 처럼 아주 작으면 10km 에 역이
     * 10만 개 생겨 계산 한 번이 몇 분이다(11-04 — 그때까지 수단 기준값에는 검사가 없었다)
     */
    static final BigDecimal MIN_SPACING_KM = new BigDecimal("0.2");

    @GetMapping("/modes")
    public List<ModeResponse> modes() {
        return modes.findAll().stream().map(ModeResponse::of).toList();
    }

    @PutMapping("/modes/{modeType}")
    @Transactional
    public ModeResponse updateMode(@PathVariable ModeType modeType,
                                   @Valid @RequestBody ModeRequest request) {
        ModeCapacity mode = modes.findById(modeType)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        modeType + " 수단이 없습니다"));
        // 비우는 것(NULL)은 된다 — 근거가 없으면 비워 두는 것이 정상이다(S1 주석)
        if (request.spacingKm() != null && request.spacingKm().compareTo(MIN_SPACING_KM) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "역간격은 " + MIN_SPACING_KM + "km 이상이어야 합니다 — 열차 한 편성(약 200m)보다 가까운 역은 없습니다");
        }
        if (request.speedKmh() != null && request.speedKmh().signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "표정속도는 0 보다 커야 합니다");
        }
        if (request.maxLengthKm() != null && request.maxLengthKm().signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "권장 연장은 0 보다 커야 합니다 — 상한이 없으면 비워 두세요");
        }
        if (request.pphpdMin() != null && request.pphpdMin() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "수요 하한은 음수일 수 없습니다");
        }
        if (request.pphpdMax() != null && request.pphpdMax() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "수송능력 상한은 음수일 수 없습니다");
        }
        if (request.pphpdMin() != null && request.pphpdMax() != null
                && request.pphpdMin() > request.pphpdMax()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "수요 하한이 수송능력 상한보다 큽니다");
        }
        mode.changeBounds(request.pphpdMin(), request.pphpdMax(), request.spacingKm(),
                request.speedKmh(), request.maxLengthKm(), request.source());
        return ModeResponse.of(mode);
    }
}
