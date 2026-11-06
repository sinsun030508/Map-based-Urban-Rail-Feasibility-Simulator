package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.PriorityQueue;

/**
 * 돌아가는 안 — 산 구간({@link MountainProfile})마다 <b>35‰ 를 넘지 않고 산을 피해 가는 선형</b>을 제안한다.
 * 바꾸는 것은 사용자다(호수 우회 {@link WaterDetour} 와 같다).
 *
 * <p>방법: 산 구간 앞뒤로 1km 를 붙인 「우회 창」 의 양 끝 A·B 사이를 100m 격자로 나누고, <b>이웃 칸 사이
 * 높이차가 35‰ 를 넘는 이동을 막은</b> A* 로 길을 찾는다 — 길은 골짜기와 등고선을 따른다. 계단 모양 길은
 * 35‰ 를 지키는 한 곧게 이어 줄인다. 찾은 길은 {@link MountainProfile} 로 다시 재서 터널이 남으면 버린다.
 *
 * <p><b>격자는 1.5배 타원만큼만 본다.</b> 길이가 원래 구간의 {@value #MAX_RATIO}배 이하인 길은 A·B 를 초점으로 하는
 * 타원 안에만 있을 수 있다 — 그 밖은 볼 필요가 없고, 그 안에 길이 없으면 「1.5배 안에 돌아갈 길이 없다」 가
 * 답이다. 바다(높이 0)와 높이를 모르는 칸은 지나지 않는다. 8방향 격자라 지그재그(스위치백)·나선은 만들지 않는다.
 *
 * <p><b>돌 수 있어도 손해면 뚫는다.</b> 돌면 시간은 늘 늘어나므로 비용까지 더 들면 돌 이유가 없다. 구간마다
 * 「늘어난 km ÷ 터널 km」 를 손익분기({@link MountainCost#breakEven})와 견준다 — 그래서 돌아가는 안은 이득인
 * 곳만 돌고 나머지는 뚫은 혼합안이고, 뚫기만 하는 안보다 비싸지 않다.
 */
public final class MountainDetour {

    /** 우회가 원래 구간보다 이만큼 넘게 길면 뚫는 편이 낫다고 본다 — 가정값(호수와 같다) */
    public static final double MAX_RATIO = WaterDetour.MAX_RATIO;
    /** 산 구간 앞뒤로 붙이는 여유 — 산기슭에서 벗어나 돌기 시작할 자리 */
    static final double APPROACH_KM = 1.0;
    private static final double CELL_KM = 0.1;
    private static final int MAX_CELLS = 600;

    /**
     * 산 구간 하나(가까운 구간은 한 창으로 합친다). {@code index} 는 1부터 — 지도 토막·패널 「구간 1」.
     * {@code costlier} 는 돌 수는 있지만 터널보다 비싸 뚫기로 한 구간이다(돌아갈 길이 없는 것과 다르다)
     */
    public record Mountain(int index, double tunnelKm, boolean detoured, boolean costlier, double addedKm,
                           String reason, List<List<double[]>> pieces) {
    }

    /** points — 돌아가는 점이 끼워진 선(제안하지 않은 구간은 그대로), mountains — 산 구간 */
    public record Plan(List<double[]> points, List<Mountain> mountains) {
    }

    private MountainDetour() {
    }

    /** 비용을 따지지 않고 돌 수 있는 곳을 모두 돈다 */
    public static Plan plan(List<double[]> route, Elevation e) {
        return plan(route, e, null);
    }

    /** @param breakEven 늘어난 km ÷ 터널 km 가 이 값 이상이면 뚫는다. null 이면 견주지 않는다(단가를 모른다) */
    public static Plan plan(List<double[]> route, Elevation e, Double breakEven) {
        List<MountainProfile.Section> sections = MountainProfile.sections(route, e);
        if (sections.isEmpty()) {
            return new Plan(route, List.of());
        }
        double[] cum = cumulative(route);
        double total = cum[cum.length - 1];

        // 우회 창 — 구간 앞뒤 1km, 겹치면 합친다
        List<double[]> windows = new ArrayList<>();
        List<List<MountainProfile.Section>> members = new ArrayList<>();
        for (MountainProfile.Section s : sections) {
            double from = Math.max(0, s.startKm() - APPROACH_KM);
            double to = Math.min(total, s.endKm() + APPROACH_KM);
            if (!windows.isEmpty() && from <= windows.get(windows.size() - 1)[1]) {
                windows.get(windows.size() - 1)[1] = to;
                members.get(members.size() - 1).add(s);
            } else {
                windows.add(new double[]{from, to});
                members.add(new ArrayList<>(List.of(s)));
            }
        }

        List<double[]> out = new ArrayList<>();
        List<Mountain> mountains = new ArrayList<>();
        int v = 0;                       // 다음에 넣을 원래 꼭짓점
        int count = route.size();        // 바꾼 뒤의 꼭짓점 수
        for (int w = 0; w < windows.size(); w++) {
            double from = windows.get(w)[0], to = windows.get(w)[1];
            double tunnel = members.get(w).stream().mapToDouble(MountainProfile.Section::tunnelKm).sum();
            List<List<double[]>> pieces = members.get(w).stream().map(MountainProfile.Section::pieces).toList();
            int index = w + 1;

            double[] a = pointAt(route, cum, from), b = pointAt(route, cum, to);
            double direct = to - from;
            List<double[]> via = route(a, b, direct, e);
            String reason;
            boolean costlier = false;
            if (via == null) {
                reason = String.format("35‰ 를 넘지 않고 %.1f배 안에서 돌아갈 길이 없습니다 — 뚫는 안만 있습니다", MAX_RATIO);
            } else {
                List<double[]> line = new ArrayList<>();
                line.add(a);
                line.addAll(via);
                line.add(b);
                double len = km(line);
                int inside = innerVertices(cum, from, to);
                int after = count - inside + via.size() + 2;
                if (!MountainProfile.sections(line, e).isEmpty()) {
                    reason = "돌아갈 길에도 35‰ 보다 가파른 곳이 남습니다 — 뚫는 안만 있습니다";
                } else if (len > direct * MAX_RATIO) {
                    reason = String.format("돌아가면 %.1f배(%.1fkm 더)라 뚫는 편이 낫습니다", len / direct, len - direct);
                } else if (breakEven != null && len - direct >= tunnel * breakEven) {
                    // 경유점 검사보다 먼저 — 손해인 우회가 경유점 자리를 차지하지 않게
                    costlier = true;
                    reason = String.format("돌아가면 +%.1fkm 라 터널 %.1fkm 보다 비쌉니다 — 뚫는 편이 낫습니다",
                            len - direct, tunnel);
                } else if (after > WaterDetour.MAX_POINTS) {
                    reason = "돌아가면 경유점이 " + WaterDetour.MAX_POINTS + "개를 넘어 저장할 수 없습니다";
                } else {
                    // 창 앞까지의 원래 꼭짓점, 그다음 a·경유점·b. 창 안의 원래 꼭짓점은 빠진다
                    while (v < route.size() && cum[v] < from - 1e-9) {
                        addIfNew(out, route.get(v++));
                    }
                    addIfNew(out, a);
                    out.addAll(via);
                    addIfNew(out, b);
                    while (v < route.size() && cum[v] <= to + 1e-9) {
                        v++;
                    }
                    count = after;
                    mountains.add(new Mountain(index, tunnel, true, false, len - direct, null, pieces));
                    continue;
                }
            }
            mountains.add(new Mountain(index, tunnel, false, costlier, 0, reason, pieces));
        }
        if (mountains.stream().noneMatch(Mountain::detoured)) {
            return new Plan(route, mountains);
        }
        while (v < route.size()) {
            addIfNew(out, route.get(v++));
        }
        return new Plan(out, mountains);
    }

    private static int innerVertices(double[] cum, double from, double to) {
        int n = 0;
        for (double c : cum) {
            if (c >= from - 1e-9 && c <= to + 1e-9) {
                n++;
            }
        }
        return n;
    }

    private static void addIfNew(List<double[]> out, double[] p) {
        if (!out.isEmpty()) {
            double[] last = out.get(out.size() - 1);
            if (Haversine.distanceKm(last[0], last[1], p[0], p[1]) < 1e-6) {
                return;
            }
        }
        out.add(p);
    }

    /** a→b 를 35‰ 안에서 잇는 중간 경유점. 1.5배 타원 안에 길이 없으면 null */
    private static List<double[]> route(double[] a, double[] b, double directKm, Elevation e) {
        Grid g = new Grid(a, b, directKm * MAX_RATIO, e);
        int start = g.cellOf(a), goal = g.cellOf(b);
        if (Double.isNaN(g.h[start]) || Double.isNaN(g.h[goal])) {
            return null;
        }
        int[] path = g.astar(start, goal);
        if (path == null) {
            return null;
        }
        // 계단 길을 줄인다 — 35‰ 를 지키는 한 앞으로 곧게 늘린다
        List<double[]> via = new ArrayList<>();
        int i = 0;
        while (i < path.length - 1) {
            int j = i + 1;
            while (j + 1 < path.length && g.straight(path[i], path[j + 1])) {
                j++;
            }
            if (j < path.length - 1) {
                via.add(g.latLng(path[j]));
            }
            i = j;
        }
        return via;
    }

    private static double[] cumulative(List<double[]> route) {
        double[] cum = new double[route.size()];
        for (int i = 1; i < route.size(); i++) {
            double[] p = route.get(i - 1), q = route.get(i);
            cum[i] = cum[i - 1] + Haversine.distanceKm(p[0], p[1], q[0], q[1]);
        }
        return cum;
    }

    /** 노선을 따라 km 지점의 좌표 */
    static double[] pointAt(List<double[]> route, double[] cum, double km) {
        for (int i = 1; i < route.size(); i++) {
            if (km <= cum[i] + 1e-9) {
                double seg = cum[i] - cum[i - 1];
                double f = seg <= 0 ? 0 : (km - cum[i - 1]) / seg;
                double[] p = route.get(i - 1), q = route.get(i);
                return new double[]{p[0] + (q[0] - p[0]) * f, p[1] + (q[1] - p[1]) * f};
            }
        }
        return route.get(route.size() - 1);
    }

    private static double km(List<double[]> line) {
        double sum = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] p = line.get(i - 1), q = line.get(i);
            sum += Haversine.distanceKm(p[0], p[1], q[0], q[1]);
        }
        return sum;
    }

    /** A·B 둘레의 높이 격자. 좌표는 A·B 가운데를 원점으로 한 km(정거원통 근사). 지나지 못하는 칸은 NaN */
    private static final class Grid {
        final double lat0, lng0, kmLat, kmLng, cell, x0, y0, ax, ay, bx, by, maxLen;
        final int nx, ny;
        final double[] h;

        Grid(double[] a, double[] b, double maxLenKm, Elevation e) {
            this.lat0 = (a[0] + b[0]) / 2;
            this.lng0 = (a[1] + b[1]) / 2;
            this.kmLat = 110.574;
            this.kmLng = 111.320 * Math.cos(Math.toRadians(lat0));
            this.maxLen = maxLenKm;
            this.ax = (a[1] - lng0) * kmLng;
            this.ay = (a[0] - lat0) * kmLat;
            this.bx = (b[1] - lng0) * kmLng;
            this.by = (b[0] - lat0) * kmLat;
            // 타원(두 초점까지 거리 합 ≤ maxLen)은 가운데서 반지름 maxLen/2 인 원 안에 든다
            double half = maxLenKm / 2 + CELL_KM;
            this.cell = Math.max(CELL_KM, 2 * half / MAX_CELLS);
            this.nx = (int) Math.ceil(2 * half / cell) + 1;
            this.ny = nx;
            this.x0 = -half;
            this.y0 = -half;
            this.h = new double[nx * ny];
            for (int r = 0; r < ny; r++) {
                for (int c = 0; c < nx; c++) {
                    double x = x0 + c * cell, y = y0 + r * cell;
                    if (!inEllipse(x, y)) {
                        h[r * nx + c] = Double.NaN;
                        continue;
                    }
                    OptionalDouble v = e.at(lat0 + y / kmLat, lng0 + x / kmLng);
                    // 바다(0)와 모르는 칸은 지나지 않는다
                    h[r * nx + c] = v.isPresent() && v.getAsDouble() > 0 ? v.getAsDouble() : Double.NaN;
                }
            }
        }

        boolean inEllipse(double x, double y) {
            // 끝점 칸이 타원 경계에 걸려도 빠지지 않게 두 칸 여유
            return Math.hypot(x - ax, y - ay) + Math.hypot(x - bx, y - by) <= maxLen + 2 * cell;
        }

        int cellOf(double[] p) {
            int col = (int) Math.round(((p[1] - lng0) * kmLng - x0) / cell);
            int row = (int) Math.round(((p[0] - lat0) * kmLat - y0) / cell);
            col = Math.max(0, Math.min(nx - 1, col));
            row = Math.max(0, Math.min(ny - 1, row));
            return row * nx + col;
        }

        double[] latLng(int idx) {
            return new double[]{lat0 + (y0 + (idx / nx) * cell) / kmLat, lng0 + (x0 + (idx % nx) * cell) / kmLng};
        }

        /** 이웃으로 갈 수 있나 — 높이차가 35‰ 이하 */
        static boolean step(double h1, double h2, double distKm) {
            return !Double.isNaN(h1) && !Double.isNaN(h2)
                    && Math.abs(h2 - h1) <= MountainProfile.GRADE * distKm * 1000;
        }

        /** 두 칸 사이를 한 칸 간격으로 훑어 매 걸음 35‰ 를 지키면 곧게 이을 수 있다 */
        boolean straight(int from, int to) {
            double r1 = from / nx, c1 = from % nx, r2 = to / nx, c2 = to % nx;
            double len = Math.hypot(r2 - r1, c2 - c1);
            int steps = Math.max(1, (int) Math.ceil(len));
            double prev = h[from];
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                double cur = bilinear(r1 + (r2 - r1) * t, c1 + (c2 - c1) * t);
                if (!step(prev, cur, len / steps * cell)) {
                    return false;
                }
                prev = cur;
            }
            return true;
        }

        /** 칸 사이 높이 — 네 이웃 중 하나라도 못 가는 칸이면 NaN */
        double bilinear(double r, double c) {
            int r0 = (int) Math.floor(r), c0 = (int) Math.floor(c);
            int r1 = Math.min(ny - 1, r0 + 1), c1 = Math.min(nx - 1, c0 + 1);
            double fr = r - r0, fc = c - c0;
            double v00 = h[r0 * nx + c0], v01 = h[r0 * nx + c1], v10 = h[r1 * nx + c0], v11 = h[r1 * nx + c1];
            return (v00 * (1 - fc) + v01 * fc) * (1 - fr) + (v10 * (1 - fc) + v11 * fc) * fr;
        }

        int[] astar(int start, int goal) {
            double[] dist = new double[nx * ny];
            Arrays.fill(dist, Double.MAX_VALUE);
            int[] prev = new int[nx * ny];
            Arrays.fill(prev, -1);
            boolean[] done = new boolean[nx * ny];
            int gr = goal / nx, gc = goal % nx;
            PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
            dist[start] = 0;
            open.add(new double[]{Math.hypot(start / nx - gr, start % nx - gc), start});
            while (!open.isEmpty()) {
                int cur = (int) open.poll()[1];
                if (done[cur]) {
                    continue;
                }
                if (cur == goal) {
                    List<Integer> rev = new ArrayList<>();
                    for (int x = goal; x != -1; x = prev[x]) {
                        rev.add(x);
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
                        double d = Math.hypot(dr, dc);
                        if (done[nb] || !step(h[cur], h[nb], d * cell)) {
                            continue;
                        }
                        double nd = dist[cur] + d;
                        if (nd < dist[nb]) {
                            dist[nb] = nd;
                            prev[nb] = cur;
                            open.add(new double[]{nd + Math.hypot(rr - gr, cc - gc), nb});
                        }
                    }
                }
            }
            return null;
        }
    }
}
