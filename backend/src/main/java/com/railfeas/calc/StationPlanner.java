package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 정차역 자동 배치 — 노선을 수단별 역간격으로 나누고, 각 지점을 인구 피크로 끌어당긴다.
 *
 * 왜 균등 분할만으로는 안 되나
 *   균등 분할은 역을 논밭 가운데 놓을 수 있다. 실제 노선은 사람이 많은 곳에 역을 둔다.
 *   그래서 ① 균등 분할로 이상 위치를 잡고 ② 그 주변 창(window) 안에서 반경 500m
 *   인구가 가장 많은 지점으로 옮긴다.
 *
 * 왜 창을 두나
 *   제한 없이 끌어당기면 역이 한 덩어리로 뭉쳐 역간격이 깨진다. 이상 위치에서
 *   ±역간격의 {@value #WINDOW_RATIO} 배까지만 움직이게 하고, 앞 역과 최소 간격도 지킨다.
 *
 * 인구는 **고정 반경 안의 합**으로 센다. 집계구는 크기가 제각각이라 집계구 인구를
 * 그대로 비교하면 넓은 시골 구역이 이기지만, 반경을 고정하면 밀도 비교가 된다.
 *
 * 한계
 *   - 종점은 사용자가 찍은 점에 고정한다. 출발·도착은 설계 의도라 옮기지 않는다
 *   - 환승·지형·용지는 보지 않는다. 기존 노선 환승은 별도 기능이다
 *   - 인구 자료가 없는 지역(수집 범위 밖)은 균등 분할 결과가 그대로 남는다
 */
@Component
public class StationPlanner {

    private static final double WINDOW_RATIO = 0.35;   // 이상 위치에서 벗어날 수 있는 한도
    private static final double STEP_KM = 0.1;         // 창 안을 훑는 간격
    private static final double SCORE_RADIUS_KM = 0.5; // 역세권으로 볼 반경
    private static final double MIN_GAP_RATIO = 0.5;   // 앞 역과 최소 간격 (역간격 대비)
    private static final double LATERAL_KM = 0.5;      // 선에서 옆으로 벗어날 수 있는 한도
    private static final double LATERAL_STEP_KM = 0.25; // 옆쪽을 훑는 간격

    private final PopulationIndex population;

    public StationPlanner(PopulationIndex population) {
        this.population = population;
    }

    /** 배치된 역 하나. score 는 반경 안 인구로, 이용객 배분에 쓴다 */
    public record Placed(double lat, double lng, String name, long score) {
    }

    /**
     * @param route    사용자가 찍은 노선 좌표 [위도, 경도]
     * @param count    배치할 역 수 (종점 2개 포함)
     * @param spacingKm 수단의 표준 역간격
     */
    /**
     * 배치 결과와 **옆으로 비켜서 늘어난 몫**(km).
     *
     * detourKm 을 따로 주는 이유: 역을 이은 선을 그냥 다시 재면 안 된다.
     * pointAt 이 위·경도를 도 단위로 직선 보간하는데, 그 보간점들 사이를 하버사인으로
     * 재면 원래 거리보다 0.08% 쯤 길어진다. 그 **보간 오차를 "돌아간 거리" 로 오인하면**
     * 역이 하나도 안 움직였는데 건설비가 오른다(실제로 10km 노선에서 8m 가 붙었다).
     * 그래서 **같은 방식으로 만든 두 선을 견준다** — 옆으로 옮긴 선과 안 옮긴 선.
     * 보간 오차는 양쪽에 똑같이 생겨 상쇄되고 옆으로 간 몫만 남는다.
     */
    public record Plan(List<Placed> stations, double detourKm) {
    }

    public List<Placed> plan(List<double[]> route, int count, double spacingKm) {
        return planWithDetour(route, count, spacingKm).stations();
    }

    public Plan planWithDetour(List<double[]> route, int count, double spacingKm) {
        List<Double> cum = cumulative(route);
        double length = cum.get(cum.size() - 1);
        if (count < 2 || length <= 0) {
            return new Plan(List.of(), 0);
        }

        double window = spacingKm * WINDOW_RATIO;
        double minGap = spacingKm * MIN_GAP_RATIO;
        List<Placed> out = new ArrayList<>(count);
        List<double[]> onLine = new ArrayList<>(count);   // 옆으로 안 옮겼다고 쳤을 때의 자리
        Map<String, Integer> usedNames = new HashMap<>();

        double previous = -1;
        for (int i = 0; i < count; i++) {
            double ideal = length * i / (count - 1);
            double chosen;
            if (i == 0 || i == count - 1) {
                chosen = ideal;                      // 종점은 사용자가 찍은 그대로
            } else {
                double from = Math.max(previous + minGap, ideal - window);
                double to = Math.min(length, ideal + window);
                chosen = from >= to ? ideal : bestWithin(route, cum, from, to);
            }
            previous = chosen;

            double[] on = pointAt(route, cum, chosen);
            // 종점은 사용자가 찍은 자리라 옆으로도 옮기지 않는다
            double[] at = (i == 0 || i == count - 1) ? on : bestBeside(route, cum, chosen, on);
            onLine.add(on);
            out.add(place(at, usedNames));
        }
        return new Plan(out, detour(out, onLine));
    }

    /** 옆으로 옮긴 선과 안 옮긴 선의 길이 차. 아무도 안 움직였으면 정확히 0 이다 */
    private static double detour(List<Placed> moved, List<double[]> onLine) {
        double a = 0;
        double b = 0;
        for (int i = 1; i < moved.size(); i++) {
            Placed p = moved.get(i - 1);
            Placed q = moved.get(i);
            a += Haversine.distanceKm(p.lat(), p.lng(), q.lat(), q.lng());
            double[] r = onLine.get(i - 1);
            double[] t = onLine.get(i);
            b += Haversine.distanceKm(r[0], r[1], t[0], t[1]);
        }
        return Math.max(0, a - b);
    }

    /**
     * 창 안을 훑어 반경 인구가 가장 많은 거리를 고른다. 동점이면 이상 위치에 가까운 쪽.
     *
     * 점수는 반경 안 인구의 **합**이라 계단 함수다 — 봉우리가 이미 반경에 들어오면 더
     * 다가가도 점수가 그대로다. 그래서 동점이 흔하고, 그때는 역간격을 지키는 쪽이 낫다.
     * 이상 위치를 먼저 재 두는 이유도 같다. 훑는 간격이 0.1km 라 격자가 이상 위치를
     * 비껴가면 인구가 없는 구간에서도 역이 수십 m 씩 밀린다.
     */
    private double bestWithin(List<double[]> route, List<Double> cum, double from, double to) {
        double mid = (from + to) / 2;
        double best = mid;
        long bestScore = score(pointAt(route, cum, mid));
        for (double d = from; d <= to + 1e-9; d += STEP_KM) {
            double[] p = pointAt(route, cum, d);
            long score = score(p);
            if (score > bestScore
                    || (score == bestScore && Math.abs(d - mid) < Math.abs(best - mid))) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    /**
     * 선 위 지점에서 **옆으로** 벗어나 더 나은 자리를 찾는다.
     *
     * 왜 필요한가
     *   역을 선 위에서만 앞뒤로 옮기면, 선에서 몇백 m 떨어진 큰 단지를 못 담는다.
     *   실제 노선도 역을 두려고 선형을 조금 비튼다.
     *
     * 왜 {@value #LATERAL_KM}km 로 막는가
     *   더 멀리 보내면 "선로를 놓을 수 있는 곳인가" 를 이 모델이 답할 수 없다 —
     *   지형·용지 자료가 없다. 걸어서 닿는 거리(역세권 반경 {@value #SCORE_RADIUS_KM}km)
     *   안쪽으로만 비튼다.
     *
     * **옆으로 가면 노선이 길어진다.** 그 대가는 plannedLengthKm 이 계산해
     * 건설비·편익에 그대로 흘러간다 — 공짜로 인구만 더 담지 않는다.
     */
    private double[] bestBeside(List<double[]> route, List<Double> cum,
                                double distance, double[] on) {
        double total = cum.get(cum.size() - 1);
        // 그 지점의 선 방향. 앞뒤 50m 를 보고 구한다 (꼭짓점에서도 흔들리지 않게)
        double[] back = pointAt(route, cum, Math.max(0, distance - 0.05));
        double[] fwd = pointAt(route, cum, Math.min(total, distance + 0.05));
        double dLat = fwd[0] - back[0];
        double dLng = fwd[1] - back[1];
        if (dLat == 0 && dLng == 0) {
            return on;
        }
        // 위도 1도와 경도 1도의 km 가 다르다. km 로 바꿔 직각을 구하고 다시 도로 되돌린다
        double kmPerLat = 110.574;
        double kmPerLng = 111.320 * Math.cos(Math.toRadians(on[0]));
        double vx = dLng * kmPerLng;
        double vy = dLat * kmPerLat;
        double norm = Math.hypot(vx, vy);
        if (norm <= 0) {
            return on;
        }
        // 직각 단위벡터 (km 평면)
        double px = -vy / norm;
        double py = vx / norm;

        double[] best = on;
        long bestScore = score(on);
        for (double off = -LATERAL_KM; off <= LATERAL_KM + 1e-9; off += LATERAL_STEP_KM) {
            if (Math.abs(off) < 1e-9) {
                continue;                       // 선 위는 이미 쟀다
            }
            double[] cand = new double[]{
                    on[0] + (py * off) / kmPerLat,
                    on[1] + (px * off) / kmPerLng};
            long s = score(cand);
            // 동점이면 선 위에 남는다. 괜히 노선을 늘리지 않는다
            if (s > bestScore) {
                bestScore = s;
                best = cand;
            }
        }
        return best;
    }

    private long score(double[] point) {
        long total = 0;
        for (PopulationIndex.Cell cell : population.censusNear(point[0], point[1], SCORE_RADIUS_KM)) {
            total += Math.round(cell.value());
        }
        return total;
    }

    /** 반경 안에서 인구가 가장 많은 집계구의 행정동 이름을 역 이름으로 쓴다 */
    private Placed place(double[] at, Map<String, Integer> usedNames) {
        List<PopulationIndex.Cell> cells =
                population.censusNear(at[0], at[1], SCORE_RADIUS_KM);
        long total = 0;
        PopulationIndex.Cell top = null;
        for (PopulationIndex.Cell cell : cells) {
            total += Math.round(cell.value());
            if (top == null || cell.value() > top.value()) {
                top = cell;
            }
        }
        String name = top == null || top.name() == null || top.name().isBlank()
                ? null : top.name();
        if (name != null) {
            // 같은 동에 역이 둘 이상 놓이면 뒤에 번호를 붙인다
            int seen = usedNames.merge(name, 1, Integer::sum);
            if (seen > 1) {
                name = name + ' ' + seen;
            }
        }
        return new Placed(at[0], at[1], name, total);
    }

    /** 각 꼭짓점까지의 누적 거리 */
    private List<Double> cumulative(List<double[]> route) {
        List<Double> cum = new ArrayList<>(route.size());
        cum.add(0.0);
        for (int i = 1; i < route.size(); i++) {
            double[] a = route.get(i - 1);
            double[] b = route.get(i);
            cum.add(cum.get(i - 1) + Haversine.distanceKm(a[0], a[1], b[0], b[1]));
        }
        return cum;
    }

    /** 기점에서 distance 만큼 간 지점. 선분 안에서는 직선 보간한다 */
    private double[] pointAt(List<double[]> route, List<Double> cum, double distance) {
        double total = cum.get(cum.size() - 1);
        double d = Math.max(0, Math.min(total, distance));
        for (int i = 1; i < cum.size(); i++) {
            if (d <= cum.get(i) || i == cum.size() - 1) {
                double segment = cum.get(i) - cum.get(i - 1);
                double ratio = segment <= 0 ? 0 : (d - cum.get(i - 1)) / segment;
                double[] a = route.get(i - 1);
                double[] b = route.get(i);
                return new double[]{
                        a[0] + (b[0] - a[0]) * ratio,
                        a[1] + (b[1] - a[1]) * ratio};
            }
        }
        return route.get(route.size() - 1);
    }
}
