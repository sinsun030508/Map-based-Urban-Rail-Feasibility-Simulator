package com.railfeas.calc;

import com.railfeas.reference.CostStandard;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 그린 노선이 산을 지나 뚫어야 하는 터널 길이(km). 비교표 건설비에 터널비를 더하는 데 쓴다({@link CostCalculator}).
 * 철도(35‰)와 도로(BRT, {@link MountainProfile#ROAD_GRADE})를 따로 잰다 — 도로는 더 가파르게 올라 터널이 짧다.
 * 비어 있으면 「모른다」 — 지형 자료가 없을 때다(0 km 와 다르다). 계산기를 지형에서 떼어 두려고 따로 둔다
 */
@FunctionalInterface
public interface TunnelKm {

    record Tunnels(OptionalDouble rail, OptionalDouble road) {
    }

    /** 지형을 보지 않는다 — 테스트와 지형 자료가 없는 환경 */
    TunnelKm NONE = (route, standards) -> new Tunnels(OptionalDouble.empty(), OptionalDouble.empty());

    /** @param standards 손익분기({@link MountainCost#breakEven})를 정해 산 구간 캐시 키를 맞추는 데 쓴다 */
    Tunnels on(List<double[]> route, List<CostStandard> standards);
}
