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
 * <p><b>km당 터널 추가분은 KDI 표준공사비다</b>(11-09, research-log 「산악 터널 단가」). 「예비타당성조사 수행을 위한 세부지침
 * 도로·철도부문 연구」(2021, 2019년 기준 토목공사비·VAT 제외)의 산악 NATM 터널 − 그 행의 기준 구조를 2025 년으로 올린다.
 * 처음엔 우리 단가표의 「지하 − 고가」 km단가 차를 썼는데, 그 지하 단가는 도심 지하철(개착이 섞인) 사업이라 산악 터널을 크게
 * 잡았다 — KDI 로는 산악 터널이 철도 교량(고가)과 km당 비슷하거나 더 싸다. <b>터널이 기준 구조보다 싸면 0</b> 이다(빼지 않는다).
 * <ul>
 *   <li><b>뚫는 안</b> = 터널 km × km당 추가분. BRT 는 도로 기울기({@link MountainProfile#ROAD_GRADE})로 잰 터널 km 로 센다</li>
 *   <li><b>돌아가는 안</b> = 늘어난 연장 × 이 행의 km단가 + 못 돌아갔거나 돌면 손해라 뚫는 구간의 터널값. 늘어난 시간 =
 *       늘어난 연장 ÷ 표정속도. 돌아갈 수 있는 구간이 하나도 없으면 null(안이 없다 — 0원이 아니다). BRT 는 철도 선형의
 *       우회라 돌아가는 안을 두지 않는다</li>
 * </ul>
 * 토목만이라 터널 환기·방재 설비는 빠진다(초장대터널은 본선 공사비의 1~1.5% — 표 Ⅲ-77).
 */
public final class MountainCost {

    private static final Set<ModeType> ROAD = Set.of(ModeType.BRT_HIGH, ModeType.BRT_LOW);

    /** 2019 → 2025 GDP 디플레이터 배수 — S4 `price_index` 2019 행과 같아야 한다(우리 단가표와 같은 물가 기준) */
    public static final double DEFLATOR_2019 = 1.1686;

    /** 도로 2차로 NATM 터널 14,584천원/m — 표 Ⅲ-16(고속국도·일반국도 같다). 2019년 억원/km */
    static final double ROAD_TUNNEL_2019 = 145.84;

    /** (터널, 기준 구조) 2019년 억원/km. 기준 구조는 그 행이 산이 없을 때 지었을 구조다 */
    private record Kdi(double tunnel, double base) {
    }

    private static final Map<ModeType, Kdi> KDI = Map.of(
            // 중량 복선 NATM 319(표 Ⅲ-122) − 복선 일반부 교량 직접기초 317(Ⅲ-72). 산악 기반암이라 직접기초
            ModeType.HEAVY_METRO, new Kdi(319, 317),
            // 경량전철 NATM 265.6 − 교량 190.8(Ⅲ-129, AGT·LIM)
            ModeType.LIGHT_RAIL, new Kdi(265.6, 190.8),
            // 복선전철 일반부 NATM 192(Ⅲ-76) − 교량 직접기초 317(Ⅲ-72) → 터널이 더 싸 0
            ModeType.DOUBLE_ELEC, new Kdi(192, 317),
            ModeType.EXPRESS_RAIL, new Kdi(192, 317),
            // 경량전철 NATM 265.6 − 노면전차 13.4(같은 표 Ⅲ-129)
            ModeType.TRAM, new Kdi(265.6, 13.4));

    /** 억원·분. null 은 「모른다」 또는 「그 안이 없다」 */
    public record Delta(ModeType mode, StructureType structure, Long tunnelExtraCost, Long detourExtraCost,
                        Double detourExtraMin) {
    }

    private MountainCost() {
    }

    /**
     * 이 행을 산악 터널로 바꿀 때 km당 추가 공사비(억원, 2025). 지하 행 0, 터널이 기준 구조보다 싸면 0.
     * BRT 는 도로 2차로 터널 − 그 행 km단가(우리 BRT 단가가 땅 위 도로 몫이다 — 출처가 섞인다). 표에 없는 수단은 null(모름)
     */
    public static Double extraPerKm(CostStandard s) {
        if (s.getStructureType() == StructureType.UNDERGROUND) {
            return 0.0;
        }
        if (ROAD.contains(s.getModeType())) {
            return Math.max(0, ROAD_TUNNEL_2019 * DEFLATOR_2019 - s.getCostPerKm());
        }
        Kdi k = KDI.get(s.getModeType());
        return k == null ? null : Math.max(0, (k.tunnel() - k.base()) * DEFLATOR_2019);
    }

    /**
     * 공통 손익분기 말고 그 수단 행만의 손익분기로 우회를 따로 제안하는 수단. 트램은 땅 위 단가(273억/km)가 싸 산악 터널 추가분
     * (295억/km)이 km단가를 넘는다 — 손익분기 약 1.08 로 철도 중앙값(약 0.004)과 수백 배 다르다(11-12 사용자 요청).
     * 경전철 고가(약 0.25)는 사용자가 정하지 않아 넣지 않았다
     */
    public static final List<ModeType> OWN_PLAN = List.of(ModeType.TRAM);

    /**
     * 돌아가는 편이 터널보다 싼 경계 — 늘어난 km × km단가 = 터널 km × km당 추가분 에서 늘어난 km ÷ 터널 km = 추가분 ÷ km단가.
     * 수단을 고르기 전에 선형을 하나로 정하려고 철도 행(BRT·지하·{@link #OWN_PLAN} 빼고)의 중앙값을 쓴다 — 따로 따지는
     * 트램이 끼면 중앙값을 끌어올려 다른 철도까지 손해인 우회를 받는다. 견줄 행이 없으면 null
     */
    public static Double breakEven(List<CostStandard> standards) {
        return median(standards.stream().filter(s -> !OWN_PLAN.contains(s.getModeType())).toList());
    }

    /** 한 수단 행만의 손익분기({@link #OWN_PLAN} 의 따로 안). 견줄 행이 없으면 null */
    public static Double breakEven(List<CostStandard> standards, ModeType mode) {
        return median(standards.stream().filter(s -> s.getModeType() == mode).toList());
    }

    private static Double median(List<CostStandard> standards) {
        List<Double> ratios = new ArrayList<>();
        for (CostStandard s : standards) {
            if (ROAD.contains(s.getModeType()) || s.getStructureType() == StructureType.UNDERGROUND) {
                continue;
            }
            Double extra = extraPerKm(s);
            if (extra != null && s.getCostPerKm() > 0) {
                ratios.add(extra / s.getCostPerKm());
            }
        }
        if (ratios.isEmpty()) {
            return null;
        }
        ratios.sort(null);
        int n = ratios.size();
        return n % 2 == 1 ? ratios.get(n / 2) : (ratios.get(n / 2 - 1) + ratios.get(n / 2)) / 2;
    }

    /** @param roadTunnelKm 도로 기울기로 잰 터널 km(BRT 행). 모르면 null */
    public static List<Delta> deltas(MountainDetour.Plan plan, List<CostStandard> standards,
                                     Map<ModeType, ModeCapacity> capacities, Double roadTunnelKm) {
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
            if (s.getStructureType() == StructureType.UNDERGROUND) {
                continue;
            }
            Double perKmExtra = extraPerKm(s);
            if (ROAD.contains(s.getModeType())) {
                Long cost = roadTunnelKm == null || perKmExtra == null ? null : Math.round(roadTunnelKm * perKmExtra);
                out.add(new Delta(s.getModeType(), s.getStructureType(), cost, null, null));
                continue;
            }
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
