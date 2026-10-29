package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import org.springframework.stereotype.Component;

/**
 * 그린 노선이 호수·저수지를 지나면 **물을 피해 가는 선형을 제안**한다. 바꾸는 것은 사용자다 —
 * 그린 선이 설계 의도이고, 화면이 "돌아가는 선형으로 바꾸기" 를 눌렀을 때만 이 결과를 쓴다.
 *
 * 방법: 물을 지나는 구간마다 그 둘레를 100m 격자로 나누고 물 칸을 막은 뒤 A* 로 가장 짧은
 * 길을 찾는다. 격자 길은 계단 모양이라, 물을 지나지 않는 한 멀리 곧게 잇는 식으로 줄여 경유점
 * 몇 개만 남긴다.
 *
 * **물가 한 칸(100m)을 더 막는다.** 칸 중심만 보면 길이 물가에 딱 붙어 모서리를 스친다.
 *
 * **돌아가는 길이 원래 구간의 {@value #MAX_RATIO}배를 넘으면 제안하지 않는다.** 소양호처럼
 * 가지가 길게 뻗은 저수지를 끝까지 돌면 수십 km 를 더하는데, 실제 철도는 그런 곳을 다리로
 * 건넌다. 이 배수는 **가정값**이다 — 다리와 우회의 비용을 견줄 교량 단가 자료가 없다
 * (`cost_standard` 는 수단 × 구조뿐이다, 노트의 「교량 할증」).
 */
@Component
public class WaterDetour {

    /** 우회가 원래 구간보다 이만큼 넘게 길면 건너는 편이 낫다고 본다 — 가정값 */
    public static final double MAX_RATIO = 1.5;
    /** 저장할 수 있는 경유점 수 (`ScenarioDto.SaveRequest`) */
    static final int MAX_POINTS = 50;
    private static final double CELL_KM = 0.1;
    private static final int MAX_CELLS = 600;          // 한 변 — 넘으면 칸을 키운다
    private static final double MARGIN_KM = 1.0;       // 호수 둘레로 더 볼 여유

    /** 지나는 물 하나(한 구간에 여럿이면 이름을 잇는다) */
    public record Crossing(String name, boolean detoured, double addedKm, String reason) {
    }

    /** points — 우회점이 끼워진 선(제안하지 않은 구간은 그대로), crossings — 지나는 물 */
    public record Plan(List<double[]> points, List<Crossing> crossings) {
    }

    private final WaterBodies water;

    public WaterDetour(WaterBodies water) {
        this.water = water;
    }

    public Plan plan(List<double[]> route) {
        List<double[]> out = new ArrayList<>();
        List<Crossing> crossings = new ArrayList<>();
        if (route.isEmpty()) {
            return new Plan(out, crossings);
        }
        out.add(route.get(0));
        for (int i = 1; i < route.size(); i++) {
            double[] a = route.get(i - 1);
            double[] b = route.get(i);
            List<WaterBodies.Body> hits = water.crossing(a, b);
            if (hits.isEmpty()) {
                out.add(b);
                continue;
            }
            String names = String.join("·", hits.stream().map(WaterBodies.Body::name).toList());
            double direct = km(List.of(a, b));
            List<double[]> via = route(a, b, hits);
            if (via == null) {
                crossings.add(new Crossing(names, false, 0, "물을 피해 갈 길을 찾지 못했습니다"));
            } else {
                List<double[]> line = new ArrayList<>();
                line.add(a);
                line.addAll(via);
                line.add(b);
                double len = km(line);
                int remaining = route.size() - i - 1;
                if (len > direct * MAX_RATIO) {
                    crossings.add(new Crossing(names, false, 0, String.format(
                            "돌아가면 %.1f배(%.1fkm 더)라 건너는 편이 낫습니다", len / direct, len - direct)));
                } else if (out.size() + via.size() + 1 + remaining > MAX_POINTS) {
                    crossings.add(new Crossing(names, false, 0,
                            "돌아가면 경유점이 " + MAX_POINTS + "개를 넘어 저장할 수 없습니다"));
                } else {
                    out.addAll(via);
                    crossings.add(new Crossing(names, true, len - direct, null));
                }
            }
            out.add(b);
        }
        return new Plan(out, crossings);
    }

    /**
     * a→b 를 물을 피해 잇는 중간 경유점들. 길이 없으면 null.
     * 둘레의 호수를 모두 막는다 — 지나는 호수만 막으면 돌아간 길이 옆 호수로 들어간다.
     */
    private List<double[]> route(double[] a, double[] b, List<WaterBodies.Body> hits) {
        double minLat = Math.min(a[0], b[0]), maxLat = Math.max(a[0], b[0]);
        double minLng = Math.min(a[1], b[1]), maxLng = Math.max(a[1], b[1]);
        for (WaterBodies.Body w : hits) {
            minLat = Math.min(minLat, w.minLat());
            maxLat = Math.max(maxLat, w.maxLat());
            minLng = Math.min(minLng, w.minLng());
            maxLng = Math.max(maxLng, w.maxLng());
        }
        Grid g = new Grid((minLat + maxLat) / 2, (minLng + maxLng) / 2,
                minLat, maxLat, minLng, maxLng);
        for (WaterBodies.Body w : water.within(g.lat(0), g.lat(g.ny), g.lng(0), g.lng(g.nx))) {
            for (double[][] ring : w.rings()) {
                g.fill(ring);
            }
        }
        g.dilate();
        int start = g.cellOf(a), goal = g.cellOf(b);
        g.blocked[start] = false;
        g.blocked[goal] = false;
        int[] path = g.astar(start, goal);
        if (path == null) {
            return null;
        }
        // 계단 길을 줄인다 — 막힌 칸을 지나지 않는 한 가장 멀리 곧게 잇는다
        List<double[]> via = new ArrayList<>();
        int i = 0;
        while (i < path.length - 1) {
            int j = path.length - 1;
            while (j > i + 1 && !g.clear(path[i], path[j])) {
                j--;
            }
            if (j < path.length - 1) {
                via.add(g.latLng(path[j]));
            }
            i = j;
        }
        return via;
    }

    private static double km(List<double[]> line) {
        double sum = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] p = line.get(i - 1), q = line.get(i);
            sum += Haversine.distanceKm(p[0], p[1], q[0], q[1]);
        }
        return sum;
    }

    /** 노선 둘레의 격자. 좌표는 가운데를 원점으로 한 km(정거원통 근사) */
    private static final class Grid {
        final double lat0, lng0, kmLat, kmLng, cell, x0, y0;
        final int nx, ny;
        final boolean[] blocked;

        Grid(double lat0, double lng0, double minLat, double maxLat, double minLng, double maxLng) {
            this.lat0 = lat0;
            this.lng0 = lng0;
            this.kmLat = 110.574;
            this.kmLng = 111.320 * Math.cos(Math.toRadians(lat0));
            double w = (maxLng - minLng) * kmLng + 2 * MARGIN_KM;
            double h = (maxLat - minLat) * kmLat + 2 * MARGIN_KM;
            this.cell = Math.max(CELL_KM, Math.max(w, h) / MAX_CELLS);
            this.nx = (int) Math.ceil(w / cell) + 1;
            this.ny = (int) Math.ceil(h / cell) + 1;
            this.x0 = (minLng - lng0) * kmLng - MARGIN_KM;
            this.y0 = (minLat - lat0) * kmLat - MARGIN_KM;
            this.blocked = new boolean[nx * ny];
        }

        double lat(int row) {
            return lat0 + (y0 + row * cell) / kmLat;
        }

        double lng(int col) {
            return lng0 + (x0 + col * cell) / kmLng;
        }

        int cellOf(double[] p) {
            int col = (int) Math.round(((p[1] - lng0) * kmLng - x0) / cell);
            int row = (int) Math.round(((p[0] - lat0) * kmLat - y0) / cell);
            col = Math.max(0, Math.min(nx - 1, col));
            row = Math.max(0, Math.min(ny - 1, row));
            return row * nx + col;
        }

        double[] latLng(int idx) {
            return new double[]{lat(idx / nx), lng(idx % nx)};
        }

        /** 스캔라인 — 행마다 경계와의 교점을 구해 그 사이 칸을 칠한다 */
        void fill(double[][] ring) {
            double[] xs = new double[ring.length];
            for (int row = 0; row < ny; row++) {
                double y = y0 + row * cell;
                int n = 0;
                for (int i = 1; i < ring.length; i++) {
                    double ya = (ring[i - 1][0] - lat0) * kmLat, yb = (ring[i][0] - lat0) * kmLat;
                    if ((ya > y) != (yb > y)) {
                        double xa = (ring[i - 1][1] - lng0) * kmLng, xb = (ring[i][1] - lng0) * kmLng;
                        xs[n++] = xa + (y - ya) * (xb - xa) / (yb - ya);
                    }
                }
                Arrays.sort(xs, 0, n);
                for (int k = 0; k + 1 < n; k += 2) {
                    int c1 = (int) Math.ceil((xs[k] - x0) / cell);
                    int c2 = (int) Math.floor((xs[k + 1] - x0) / cell);
                    for (int c = Math.max(0, c1); c <= Math.min(nx - 1, c2); c++) {
                        blocked[row * nx + c] = true;
                    }
                }
            }
            // 경계선이 지나는 칸도 막는다. 위 스캔라인은 「칸 가운데가 물 안」 인 칸만 칠해서,
            // 한 칸(100m)보다 가는 물줄기·뾰족한 끝은 어느 칸 가운데에도 안 걸려 통째로 빠진다 —
            // 신갈저수지 동쪽 끝이 그랬고, 길이 그 끝을 2.5m 차이로 스쳤다
            for (int i = 1; i < ring.length; i++) {
                double ya = (ring[i - 1][0] - lat0) * kmLat, xa = (ring[i - 1][1] - lng0) * kmLng;
                double yb = (ring[i][0] - lat0) * kmLat, xb = (ring[i][1] - lng0) * kmLng;
                int steps = (int) Math.ceil(Math.hypot(xb - xa, yb - ya) / (cell / 4)) + 1;
                for (int s = 0; s <= steps; s++) {
                    double t = (double) s / steps;
                    int c = (int) Math.round((xa + (xb - xa) * t - x0) / cell);
                    int r = (int) Math.round((ya + (yb - ya) * t - y0) / cell);
                    if (r >= 0 && r < ny && c >= 0 && c < nx) {
                        blocked[r * nx + c] = true;
                    }
                }
            }
        }

        /** 물 칸 둘레 한 칸을 더 막는다 — 길이 물가 모서리를 스치지 않게 */
        void dilate() {
            boolean[] orig = blocked.clone();
            for (int r = 0; r < ny; r++) {
                for (int c = 0; c < nx; c++) {
                    if (!orig[r * nx + c]) {
                        continue;
                    }
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int rr = r + dr, cc = c + dc;
                            if (rr >= 0 && rr < ny && cc >= 0 && cc < nx) {
                                blocked[rr * nx + cc] = true;
                            }
                        }
                    }
                }
            }
        }

        /** 두 칸 사이를 반 칸 간격으로 훑어 막힌 칸이 없으면 곧게 이을 수 있다 */
        boolean clear(int from, int to) {
            double r1 = from / nx, c1 = from % nx, r2 = to / nx, c2 = to % nx;
            int steps = (int) Math.ceil(Math.hypot(r2 - r1, c2 - c1) * 2);
            for (int s = 0; s <= steps; s++) {
                double t = steps == 0 ? 0 : (double) s / steps;
                int r = (int) Math.round(r1 + (r2 - r1) * t), c = (int) Math.round(c1 + (c2 - c1) * t);
                if (blocked[r * nx + c]) {
                    return false;
                }
            }
            return true;
        }

        int[] astar(int start, int goal) {
            double[] g = new double[nx * ny];
            Arrays.fill(g, Double.MAX_VALUE);
            int[] prev = new int[nx * ny];
            Arrays.fill(prev, -1);
            boolean[] done = new boolean[nx * ny];
            int gr = goal / nx, gc = goal % nx;
            PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
            g[start] = 0;
            open.add(new double[]{Math.hypot(start / nx - gr, start % nx - gc), start});
            while (!open.isEmpty()) {
                int cur = (int) open.poll()[1];
                if (done[cur]) {
                    continue;
                }
                if (cur == goal) {
                    List<Integer> rev = new ArrayList<>();
                    for (int v = goal; v != -1; v = prev[v]) {
                        rev.add(v);
                    }
                    int[] path = new int[rev.size()];
                    for (int k = 0; k < path.length; k++) {
                        path[k] = rev.get(path.length - 1 - k);
                    }
                    return path;
                }
                done[cur] = true;
                int r = cur / nx, c = cur % nx;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        if (dr == 0 && dc == 0) {
                            continue;
                        }
                        int rr = r + dr, cc = c + dc;
                        if (rr < 0 || rr >= ny || cc < 0 || cc >= nx) {
                            continue;
                        }
                        int nb = rr * nx + cc;
                        if (blocked[nb] || done[nb]) {
                            continue;
                        }
                        double ng = g[cur] + Math.hypot(dr, dc);
                        if (ng < g[nb]) {
                            g[nb] = ng;
                            prev[nb] = cur;
                            open.add(new double[]{ng + Math.hypot(rr - gr, cc - gc), nb});
                        }
                    }
                }
            }
            return null;
        }
    }
}
