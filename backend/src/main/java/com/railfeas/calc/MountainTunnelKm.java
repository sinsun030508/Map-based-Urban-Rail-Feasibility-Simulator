package com.railfeas.calc;

import com.railfeas.reference.CostStandard;
import java.util.List;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 산 구간 판정({@link MountainDetour} — Redis 캐시 {@link MountainPlans})의 터널 길이 합. 화면이 계산 전에 같은 노선으로
 * 산 구간을 먼저 물어 두므로 대개 캐시에서 나온다. <b>판정이 실패하면 「모른다」</b> — 0 km 로 두면 터널이 없는 것처럼 싸게 나온다
 */
@Component
public class MountainTunnelKm implements TunnelKm {

    private static final Logger log = LoggerFactory.getLogger(MountainTunnelKm.class);

    private final Terrain terrain;
    private final MountainPlans plans;

    public MountainTunnelKm(Terrain terrain, MountainPlans plans) {
        this.terrain = terrain;
        this.plans = plans;
    }

    @Override
    public Tunnels on(List<double[]> route, List<CostStandard> standards) {
        if (!terrain.available() || route.size() < 2) {
            return TunnelKm.NONE.on(route, standards);
        }
        try {
            MountainDetour.Plan plan = plans.plan(route, terrain, MountainCost.breakEven(standards));
            double rail = plan.mountains().stream().mapToDouble(MountainDetour.Mountain::tunnelKm).sum();
            // 도로는 철도보다 가파르게 오르므로 철도 산 구간이 없으면 도로도 없다
            double road = rail == 0 ? 0 : roadKm(route, terrain);
            return new Tunnels(OptionalDouble.of(rail), OptionalDouble.of(road));
        } catch (RuntimeException e) {
            log.warn("산 구간을 판정하지 못해 터널비를 모른다고 둡니다 — {}", e.getMessage());
            return TunnelKm.NONE.on(route, standards);
        }
    }

    /** 도로 기울기({@link MountainProfile#ROAD_GRADE})로 잰 터널 km — BRT 행. 선형 탐색이 없어 캐시 없이 잰다 */
    public static double roadKm(List<double[]> route, Elevation terrain) {
        return MountainProfile.sections(route, terrain, MountainProfile.ROAD_GRADE).stream()
                .mapToDouble(MountainProfile.Section::tunnelKm).sum();
    }
}
