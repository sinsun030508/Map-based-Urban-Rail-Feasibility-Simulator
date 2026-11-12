package com.railfeas.route;

import com.railfeas.calc.MountainCost;
import com.railfeas.calc.MountainDetour;
import com.railfeas.calc.MountainPlans;
import com.railfeas.calc.MountainTunnelKm;
import com.railfeas.calc.RestrictedZones;
import com.railfeas.calc.Terrain;
import com.railfeas.calc.WaterBodies;
import com.railfeas.calc.WaterDetour;
import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.CostStandardRepository;
import com.railfeas.reference.ModeCapacity;
import com.railfeas.reference.ModeCapacityRepository;
import com.railfeas.reference.RestrictedZoneRepository;
import com.railfeas.scenario.ScenarioDto;
import com.railfeas.scenario.ScenarioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 그린 선형을 고치는 **제안**만 한다 — 저장하지 않는다. 화면이 「돌아가는 선형으로 바꾸기」 를
 * 눌렀을 때 이 결과로 점을 바꾸고, 저장·계산은 평소처럼 사용자가 한다.
 *
 * `waterKm` 은 **그린 선 그대로** 물 위를 지나는 길이다(강·호수). 교량 할증을 못 넣은 몫이 얼마나
 * 되는지 보여 준다. 우회를 적용하면 다음 요청에서 새 선으로 다시 잰다.
 *
 * **지하(`UNDERGROUND`)는 우회를 제안하지 않는다** — 지하철은 호수 밑을 그대로 지나면 된다.
 * 돌아가라고 하면 틀린 안내다. 고가·지상은 물 위를 지나려면 다리가 필요하므로 제안한다.
 * `waterKm` 은 구조와 상관없이 준다(지하면 하저터널 할증이 없다는 것을 알려야 한다).
 * 구조가 비어 오면 지상 쪽으로 보고 제안한다 — 예전 요청이 조용히 제안을 잃지 않게.
 *
 * `/mountain` 은 **35‰ 보다 가파른 산**을 지나는 구간과 두 안(뚫기·돌아가기)을 준다. 규칙은 호수와 같다 —
 * 지하는 안내하지 않고, 바꾸는 것은 사용자다. 지형 타일이 없으면 `available: false` 로 꺼진다(「산 없음」 이
 * 아니다). 노선 길이 상한은 저장·계산과 같다(`ScenarioService.MAX_ROUTE_KM`).
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "선형 제안")
@RestController
@RequestMapping("/api/route")
public class RouteController {

    public record DetourRequest(
            @NotNull @Size(min = 2, max = 50) List<ScenarioDto.@Valid PointRequest> points,
            StructureType structure) {
    }

    public record Point(BigDecimal latitude, BigDecimal longitude) {
    }

    public record DetourResponse(List<Point> points, List<WaterDetour.Crossing> crossings,
                                 List<WaterBodies.Span> waterKm) {
    }

    /**
     * available — 지형 자료가 있나. points — 돌아갈 수 있는 구간을 돌아간 선. roadTunnelKm — 도로(BRT) 기울기로 잰 터널 km.
     * modePlans — 손익분기가 철도 공통값과 크게 달라 따로 따진 수단의 안({@link MountainCost#OWN_PLAN}, 지금은 트램)
     */
    public record MountainResponse(boolean available, List<Point> points, List<MountainDetour.Mountain> mountains,
                                   List<MountainCost.Delta> deltas, Double roadTunnelKm, List<ModePlan> modePlans) {
    }

    /**
     * 지나는 확인 필요 구역(노선을 따라 닿는 순)과 피해 가는 선형. points 는 피할 수 있는 구역만 돌아간 선이다(못 피한 구역은 그대로).
     * 구조와 상관없이 준다 — 문화재·상수원 구역은 지하 굴착이 문제다
     */
    public record ZoneResponse(List<Point> points, List<RestrictedZones.Zone> zones, List<WaterDetour.Crossing> crossings) {
    }

    /** 한 수단만의 손익분기로 다시 따진 안 — 화면은 비교표에서 이 수단 행을 고르면 공통 안 대신 이것을 보인다 */
    public record ModePlan(ModeType mode, List<Point> points, List<MountainDetour.Mountain> mountains,
                           List<MountainCost.Delta> deltas) {
    }

    private final WaterDetour detour;
    private final WaterBodies water;
    private final Terrain terrain;
    private final CostStandardRepository standards;
    private final ModeCapacityRepository capacities;
    private final MountainPlans plans;
    private final RestrictedZoneRepository restricted;

    public RouteController(WaterDetour detour, WaterBodies water, Terrain terrain,
                           CostStandardRepository standards, ModeCapacityRepository capacities,
                           MountainPlans plans, RestrictedZoneRepository restricted) {
        this.restricted = restricted;
        this.detour = detour;
        this.water = water;
        this.terrain = terrain;
        this.standards = standards;
        this.capacities = capacities;
        this.plans = plans;
    }

    @PostMapping("/detour")
    public DetourResponse detour(@Valid @RequestBody DetourRequest request) {
        ScenarioService.lengthOf(request.points());
        List<double[]> route = coords(request);
        WaterDetour.Plan plan = request.structure() == StructureType.UNDERGROUND
                ? new WaterDetour.Plan(route, List.of())
                : detour.plan(route);
        return new DetourResponse(points(plan.points()), plan.crossings(), water.onWater(route));
    }

    @PostMapping("/mountain")
    public MountainResponse mountain(@Valid @RequestBody DetourRequest request) {
        ScenarioService.lengthOf(request.points());
        List<double[]> route = coords(request);
        if (!terrain.available()) {
            return new MountainResponse(false, points(route), List.of(), List.of(), null, List.of());
        }
        if (request.structure() == StructureType.UNDERGROUND) {
            return new MountainResponse(true, points(route), List.of(), List.of(), null, List.of());
        }
        List<CostStandard> active = standards.findByActiveTrue();
        MountainDetour.Plan plan = plans.plan(route, terrain, MountainCost.breakEven(active));
        Map<ModeType, ModeCapacity> byMode = capacities.findAll().stream()
                .collect(Collectors.toMap(ModeCapacity::getModeType, Function.identity()));
        // 도로는 철도보다 가파르게 오르므로 철도 산 구간이 없으면 도로도 없다
        Double road = plan.mountains().isEmpty() ? 0.0 : MountainTunnelKm.roadKm(route, terrain);
        if (plan.mountains().isEmpty()) {
            return new MountainResponse(true, points(plan.points()), List.of(), List.of(), road, List.of());
        }
        // 트램은 땅 위 단가가 싸 터널 추가분이 km단가를 넘는다(손익분기 약 1.08 — 철도 공통값 약 0.004) — 공통 안은
        // 거의 돌지 않아 트램에 이득인 우회가 묻혔다(11-12 사용자 요청). 표 건설비는 그린 노선 그대로라 바뀌지 않는다
        List<ModePlan> own = new ArrayList<>();
        for (ModeType mode : MountainCost.OWN_PLAN) {
            Double breakEven = MountainCost.breakEven(active, mode);
            if (breakEven == null) {
                continue;
            }
            MountainDetour.Plan p = plans.plan(route, terrain, breakEven);
            List<CostStandard> rows = active.stream().filter(s -> s.getModeType() == mode).toList();
            own.add(new ModePlan(mode, points(p.points()), p.mountains(), MountainCost.deltas(p, rows, byMode, road)));
        }
        return new MountainResponse(true, points(plan.points()), plan.mountains(),
                MountainCost.deltas(plan, active, byMode, road), road, own);
    }

    /** 확인 필요 구역 — 선이 원을 지나면 알리고 피해 가는 선형을 제안한다(11-12 사용자 요청). 바꾸는 것은 사용자다 */
    @PostMapping("/zones")
    public ZoneResponse zones(@Valid @RequestBody DetourRequest request) {
        ScenarioService.lengthOf(request.points());
        List<double[]> route = coords(request);
        List<RestrictedZones.Zone> all = RestrictedZones.of(restricted.findAll());
        List<RestrictedZones.Zone> hit = RestrictedZones.crossed(route, all);
        if (hit.isEmpty()) {
            return new ZoneResponse(points(route), List.of(), List.of());
        }
        // 지나는 구역만이 아니라 둘레 구역도 막는다 — 돌아간 길이 옆 구역으로 들어가지 않게(호수와 같다)
        WaterDetour.Plan plan = RestrictedZones.detour(all).plan(route);
        return new ZoneResponse(points(plan.points()), hit, plan.crossings());
    }

    private static List<double[]> coords(DetourRequest request) {
        return request.points().stream()
                .map(p -> new double[]{p.latitude().doubleValue(), p.longitude().doubleValue()})
                .toList();
    }

    private static List<Point> points(List<double[]> line) {
        return line.stream().map(p -> new Point(scale(p[0]), scale(p[1]))).toList();
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(7, RoundingMode.HALF_UP);
    }
}
