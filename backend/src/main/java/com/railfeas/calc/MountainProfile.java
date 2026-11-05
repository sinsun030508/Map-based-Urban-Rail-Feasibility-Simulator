package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 뚫는 안 — 그린 선 그대로 가며 **법정 최대 기울기 35‰** 를 지키려면 어디를 터널로 파야 하나.
 *
 * <p>35‰ 는 도시철도건설규칙 제15조와 철도의 건설기준에 관한 규정 제10조의 본선 한도다(우리 철도 수단 전부).
 * 설계: {@code docs/superpowers/specs/2026-11-05-mountain-detour-design.md}.
 *
 * <p><b>선로 높이를 어떻게 정하나.</b> 지면 아래로만 35‰ 를 지키는 선 L(터널만 쓰는 안)과 지면 위로만 지키는
 * 선 U(교량만 쓰는 안)를 구하고, 둘이 지면과 갈라지는 구간마다 <b>땅과 덜 어긋나는 쪽</b>을 고른다 — 산은
 * 깎는 쪽(L, 터널), 골짜기는 돋우는 쪽(U, 교량·성토)이 덜 어긋난다. 처음엔 L 만 썼는데 깊은 골짜기 옆의 평평한
 * 고원 밑에 수백 m 가짜 터널이 생겼다(선로가 골짜기 바닥까지 내려갔다 35‰ 로만 올라와서). 교량에는 할증이
 * 없다 — 지금 모델의 한계와 같다.
 *
 * <p><b>터널</b>은 지면이 선로보다 {@value #COVER_M}m 넘게 높은 곳이다(덮개 두께, 가정값 — 그보다 얕으면
 * 깎기로 본다). <b>높이를 모르는 표본</b>이 끼면 그 앞뒤로 판정하지 않는다 — 「산 없음」 이 아니라 「모름」 이다.
 */
public final class MountainProfile {

    public static final double GRADE = 0.035;
    public static final double COVER_M = 20;
    public static final double STEP_KM = 0.1;
    /** 이만큼 이하로 떨어진 터널 토막은 한 구간으로 본다(가정값) — 안 합치면 산맥 하나가 0.1km 토막 수십 개로 쪼개진다 */
    public static final double MERGE_GAP_KM = 0.5;

    /** 터널이 필요한 한 토막. {@code pieces} 는 지도에 그릴 그 토막의 좌표 */
    public record Section(double startKm, double endKm, double tunnelKm, double maxCoverM,
                          List<double[]> pieces) {
    }

    private MountainProfile() {
    }

    public static List<Section> sections(List<double[]> route, Elevation elevation) {
        List<double[]> pts = sample(route);
        int n = pts.size();
        if (n < 2) {
            return List.of();
        }
        double[] s = new double[n];
        for (int i = 1; i < n; i++) {
            double[] p = pts.get(i - 1), q = pts.get(i);
            s[i] = s[i - 1] + Haversine.distanceKm(p[0], p[1], q[0], q[1]);
        }
        double[] g = new double[n];
        boolean[] known = new boolean[n];
        for (int i = 0; i < n; i++) {
            OptionalDouble h = elevation.at(pts.get(i)[0], pts.get(i)[1]);
            known[i] = h.isPresent();
            g[i] = h.isPresent() ? Math.max(0, h.getAsDouble()) : 0;
        }
        List<Section> out = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (!known[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < n && known[j + 1]) {
                j++;
            }
            // 모르는 표본과 맞닿은 끝은 열린 끝이다 — 그 끝에 닿는 터널은 판정하지 않는다
            boolean openStart = i > 0, openEnd = j < n - 1;
            run(pts, s, g, i, j, openStart, openEnd, out);
            i = j + 1;
        }
        return out;
    }

    private static void run(List<double[]> pts, double[] s, double[] g, int a, int b,
                            boolean openStart, boolean openEnd, List<Section> result) {
        List<Section> out = new ArrayList<>();
        int m = b - a + 1;
        double[] lower = new double[m], upper = new double[m];
        for (int k = 0; k < m; k++) {
            lower[k] = g[a + k];
            upper[k] = g[a + k];
        }
        for (int k = 1; k < m; k++) {
            double d = GRADE * (s[a + k] - s[a + k - 1]) * 1000;
            lower[k] = Math.min(lower[k], lower[k - 1] + d);
            upper[k] = Math.max(upper[k], upper[k - 1] - d);
        }
        for (int k = m - 2; k >= 0; k--) {
            double d = GRADE * (s[a + k + 1] - s[a + k]) * 1000;
            lower[k] = Math.min(lower[k], lower[k + 1] + d);
            upper[k] = Math.max(upper[k], upper[k + 1] - d);
        }
        // 둘 중 하나라도 지면과 갈라지는 곳이 「흐트러진 구간」 이다. 구간마다 어긋남 면적이 작은 쪽을 고른다
        int k = 0;
        while (k < m) {
            if (!disturbed(g[a + k], lower[k], upper[k])) {
                k++;
                continue;
            }
            int e = k;
            while (e + 1 < m && disturbed(g[a + e + 1], lower[e + 1], upper[e + 1])) {
                e++;
            }
            double cut = 0, fill = 0;
            for (int t = k; t <= e; t++) {
                cut += g[a + t] - lower[t];
                fill += upper[t] - g[a + t];
            }
            if (cut < fill) {
                tunnels(pts, s, g, lower, a, k, e, openStart && k == 0, openEnd && e == m - 1, out);
            }
            k = e + 1;
        }
        result.addAll(merge(out));
    }

    /** 틈이 {@value #MERGE_GAP_KM}km 이하인 이웃 토막을 합친다. 터널 길이는 터널 표본만 — 틈은 넣지 않는다 */
    static List<Section> merge(List<Section> raw) {
        List<Section> out = new ArrayList<>();
        for (Section next : raw) {
            Section prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && next.startKm() - prev.endKm() <= MERGE_GAP_KM + 1e-9) {
                List<double[]> pieces = new ArrayList<>(prev.pieces());
                pieces.addAll(next.pieces());
                out.set(out.size() - 1, new Section(prev.startKm(), next.endKm(),
                        prev.tunnelKm() + next.tunnelKm(), Math.max(prev.maxCoverM(), next.maxCoverM()), pieces));
            } else {
                out.add(next);
            }
        }
        return out;
    }

    private static boolean disturbed(double ground, double lower, double upper) {
        return ground - lower > 1e-6 || upper - ground > 1e-6;
    }

    private static void tunnels(List<double[]> pts, double[] s, double[] g, double[] rail, int a,
                                int from, int to, boolean touchesUnknownStart, boolean touchesUnknownEnd,
                                List<Section> out) {
        int t = from;
        while (t <= to) {
            if (g[a + t] - rail[t] <= COVER_M) {
                t++;
                continue;
            }
            int e = t;
            double cover = 0;
            while (e + 1 <= to && g[a + e + 1] - rail[e + 1] > COVER_M) {
                e++;
            }
            boolean unknownEdge = (touchesUnknownStart && t == from) || (touchesUnknownEnd && e == to);
            if (!unknownEdge) {
                List<double[]> pieces = new ArrayList<>();
                for (int q = t; q <= e; q++) {
                    pieces.add(pts.get(a + q));
                    cover = Math.max(cover, g[a + q] - rail[q]);
                }
                // 표본 하나가 대표하는 길이 — 표본 수 × 간격
                out.add(new Section(s[a + t], s[a + e], (e - t + 1) * STEP_KM, cover, pieces));
            }
            t = e + 1;
        }
    }

    /** 노선을 {@value #STEP_KM}km 마다 자른 점. 양 끝을 반드시 포함한다 */
    static List<double[]> sample(List<double[]> route) {
        List<double[]> out = new ArrayList<>();
        if (route.isEmpty()) {
            return out;
        }
        out.add(route.get(0));
        for (int i = 1; i < route.size(); i++) {
            double[] p = route.get(i - 1), q = route.get(i);
            double len = Haversine.distanceKm(p[0], p[1], q[0], q[1]);
            // 꼭짓점마다 새로 센다 — 꼭짓점은 반드시 표본이 된다
            for (double at = STEP_KM; at < len; at += STEP_KM) {
                double f = at / len;
                out.add(new double[]{p[0] + (q[0] - p[0]) * f, p[1] + (q[1] - p[1]) * f});
            }
            out.add(q);
        }
        return out;
    }
}
