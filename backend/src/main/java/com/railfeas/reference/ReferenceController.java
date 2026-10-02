package com.railfeas.reference;

import com.railfeas.calc.PopulationIndex;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 프론트엔드가 수단 목록·규제 구역을 그리려면 로그인 전에도 읽어야 해서 공개한다. */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    /** 화면 한 번에 내보낼 집계구 상한 — 넘으면 잘라서 준다 */
    private static final int CELL_LIMIT = 20_000;

    private final ModeCapacityRepository modes;
    private final RestrictedZoneRepository zones;
    private final PopulationIndex population;
    private final BenefitParameterRepository parameters;

    public ReferenceController(ModeCapacityRepository modes, RestrictedZoneRepository zones,
                               PopulationIndex population, BenefitParameterRepository parameters) {
        this.modes = modes;
        this.zones = zones;
        this.population = population;
        this.parameters = parameters;
    }

    @GetMapping("/modes")
    public List<ModeCapacity> modes() {
        return modes.findAll();
    }

    /**
     * 기준값이 마지막으로 바뀐 시각. 시나리오의 `calculatedAt` 보다 뒤면 저장된 결과가 낡은 것이다.
     * 관리자가 원단위를 고쳐도 이미 계산해 둔 B/C 는 그대로 남기 때문에 화면에서 알려 줘야 한다.
     */
    @GetMapping("/updated-at")
    public LocalDateTime referenceUpdatedAt() {
        LocalDateTime a = parameters.lastUpdatedAt();
        LocalDateTime b = modes.lastUpdatedAt();
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    @GetMapping("/zones")
    public List<RestrictedZone> zones() {
        return zones.findAll();
    }

    /**
     * 화면 범위 안의 집계구 인구 — 3D 인구밀도 표시용.
     * 객체가 아니라 [위도, 경도, 인구] 배열로 보낸다. 칸이 많아 키 이름이 응답의 절반을 차지한다.
     * 수집한 77개 시군구(수도권 위주) 밖은 비어 있다.
     */
    @GetMapping("/population")
    public List<double[]> population(@RequestParam double minLat, @RequestParam double minLng,
                                     @RequestParam double maxLat, @RequestParam double maxLng) {
        return population.cellsWithin(minLat, minLng, maxLat, maxLng, CELL_LIMIT);
    }
}
