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

    @PutMapping("/parameters/{name}")
    @Transactional
    public ParameterResponse updateParameter(@PathVariable String name,
                                             @Valid @RequestBody ParameterRequest request) {
        BenefitParameter parameter = parameters.findByParamName(name)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        name + " 기준값이 없습니다"));
        parameter.changeValue(request.value(), request.source());
        return ParameterResponse.of(parameter);
    }

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
