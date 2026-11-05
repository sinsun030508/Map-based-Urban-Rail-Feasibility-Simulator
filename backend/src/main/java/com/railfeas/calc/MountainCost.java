package com.railfeas.calc;

import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.ModeCapacity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 산 구간 두 안의 비용 차이 — (수단×구조) 행마다.
 *
 * <p>앱의 건설비는 「고정비 + km단가×연장 + 역단가×역수」 다({@link CostCalculator}). 그래서
 * <ul>
 *   <li><b>뚫는 안</b> = 터널 T km × (그 수단의 지하 km단가 − 이 행의 km단가). 지하 단가가 없는 수단(트램)은
 *       <b>모른다</b>(null) — 추정하지 않는다</li>
 *   <li><b>돌아가는 안</b> = 늘어난 연장 × 이 행의 km단가 + <b>못 돌아간 구간의 터널</b>값. 늘어난 시간 =
 *       늘어난 연장 ÷ 표정속도. 돌아갈 수 있는 구간이 하나도 없으면 null(안이 없다 — 0원이 아니다)</li>
 * </ul>
 * 터널 단가는 외삽이다 — 지하 단가를 만든 표본은 도심 지하철·대심도 광역철도이고 산악 터널 노선이 없다.
 * 역 수 변화와 늘어난 연장의 편익 변화는 넣지 않는다. <b>지하 행과 BRT 는 뺀다</b> — 지하는 이미 땅속이고,
 * BRT 는 도로 기준(도로의 구조·시설기준에 관한 규칙)을 따로 따른다.
 */
public final class MountainCost {

    private static final Set<ModeType> ROAD = Set.of(ModeType.BRT_HIGH, ModeType.BRT_LOW);

    /** 억원·분. null 은 「모른다」 또는 「그 안이 없다」 */
    public record Delta(ModeType mode, StructureType structure, Long tunnelExtraCost, Long detourExtraCost,
                        Double detourExtraMin) {
    }

    private MountainCost() {
    }

    public static List<Delta> deltas(MountainDetour.Plan plan, List<CostStandard> standards,
                                     Map<ModeType, ModeCapacity> capacities) {
        double tunnelAll = 0, tunnelRest = 0, added = 0;
        boolean anyDetour = false;
        for (MountainDetour.Mountain m : plan.mountains()) {
            tunnelAll += m.tunnelKm();
            if (m.detoured()) {
                anyDetour = true;
                added += m.addedKm();
            } else {
                tunnelRest += m.tunnelKm();
            }
        }
        List<Delta> out = new ArrayList<>();
        for (CostStandard s : standards) {
            if (ROAD.contains(s.getModeType()) || s.getStructureType() == StructureType.UNDERGROUND) {
                continue;
            }
            Long underground = standards.stream()
                    .filter(u -> u.getModeType() == s.getModeType() && u.getStructureType() == StructureType.UNDERGROUND)
                    .map(CostStandard::getCostPerKm)
                    .findFirst().orElse(null);
            Long perKmExtra = underground == null ? null : underground - s.getCostPerKm();

            Long tunnelCost = perKmExtra == null ? null : Math.round(tunnelAll * perKmExtra);
            Long detourCost = null;
            Double detourMin = null;
            if (anyDetour) {
                Long rest = tunnelRest == 0 ? Long.valueOf(0) : perKmExtra == null ? null : Math.round(tunnelRest * perKmExtra);
                detourCost = rest == null ? null : Math.round(added * s.getCostPerKm()) + rest;
                ModeCapacity c = capacities.get(s.getModeType());
                if (c != null && c.getSpeedKmh() != null && c.getSpeedKmh().doubleValue() > 0) {
                    detourMin = Math.round(added / c.getSpeedKmh().doubleValue() * 60 * 10) / 10.0;
                }
            }
            out.add(new Delta(s.getModeType(), s.getStructureType(), tunnelCost, detourCost, detourMin));
        }
        return out;
    }
}
