package com.railfeas.route;

import com.railfeas.calc.WaterBodies;
import com.railfeas.calc.WaterDetour;
import com.railfeas.common.StructureType;
import com.railfeas.scenario.ScenarioDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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
 */
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

    private final WaterDetour detour;
    private final WaterBodies water;

    public RouteController(WaterDetour detour, WaterBodies water) {
        this.detour = detour;
        this.water = water;
    }

    @PostMapping("/detour")
    public DetourResponse detour(@Valid @RequestBody DetourRequest request) {
        List<double[]> route = request.points().stream()
                .map(p -> new double[]{p.latitude().doubleValue(), p.longitude().doubleValue()})
                .toList();
        WaterDetour.Plan plan = request.structure() == StructureType.UNDERGROUND
                ? new WaterDetour.Plan(route, List.of())
                : detour.plan(route);
        return new DetourResponse(plan.points().stream()
                .map(p -> new Point(scale(p[0]), scale(p[1])))
                .toList(), plan.crossings(), water.onWater(route));
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(7, RoundingMode.HALF_UP);
    }
}
