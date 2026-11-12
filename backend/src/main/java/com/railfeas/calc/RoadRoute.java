package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 트램·BRT 의 노선 — 그린 구간(점 i → i+1)마다 기존 도로를 따라갈지, 새 도로를 놓을지 정한다(11-12 사용자 요청, 자동 적용).
 *
 * <p>땅 위 도로를 쓰는 수단이 그린 직선 그대로 가면 다리 없는 강을 건너는 도로를 새로 놓는 셈이었다. <b>기존 도로가 직선의
 * {@value #MAX_RATIO}배 이하면 도로를 따라간다</b>(호수·산 우회와 같은 가정값). 넘게 돌거나 도로를 못 찾으면 그 구간만 직선 새 도로다.
 * 새 도로의 땅 위 공사비는 KDI 지침에 km 표준이 없어 판정은 길이로만 한다(설계 문서 4). 구간 일부만 새 다리로 잇는 혼합은 찾지 않는다 —
 * 경유점을 찍어 구간을 나누면 된다.
 */
public final class RoadRoute {

    public static final double MAX_RATIO = 1.5;
    /**
     * 새 도로가 강·호수를 건너는 다리 — KDI 2021 세부지침 표 Ⅲ-14 일반국도 교량형식별 표준공사비, 편도 2차로(폭 11.5m) PSC Box
     * 38,601천원/m = 386.01억원/km(2019, VAT 제외 — 큰 강을 건너는 다리에 흔한 형식). 지침은 바다·큰 강 다리는 더 잡을 수 있다고 한다 — 하한이다
     */
    static final double BRIDGE_2019 = 386.01;
    public static final double BRIDGE_PER_KM = BRIDGE_2019 * MountainCost.DEFLATOR_2019;
    /** 저장할 때 선을 줄이는 오차(km) — 서울~부산 도로 경로가 수천 점이라 */
    private static final double STORE_TOLERANCE_KM = 0.02;

    /** road — 기존 도로를 따라가나. reason — 새 도로로 둔 까닭(도로면 null) */
    public record Piece(boolean road, List<double[]> points, double km, String reason) {
    }

    public record Plan(List<Piece> pieces) {
        public double km() {
            return pieces.stream().mapToDouble(Piece::km).sum();
        }

        public double roadKm() {
            return pieces.stream().filter(Piece::road).mapToDouble(Piece::km).sum();
        }

        public double newKm() {
            return pieces.stream().filter(p -> !p.road()).mapToDouble(Piece::km).sum();
        }

        public List<Piece> newPieces() {
            return pieces.stream().filter(p -> !p.road()).toList();
        }

        /** 이은 선 — 구간 사이 겹치는 점은 한 번만 */
        public List<double[]> line() {
            List<double[]> out = new ArrayList<>();
            for (Piece p : pieces) {
                for (double[] pt : p.points()) {
                    if (out.isEmpty() || Haversine.distanceKm(out.get(out.size() - 1)[0], out.get(out.size() - 1)[1], pt[0], pt[1]) > 1e-6) {
                        out.add(pt);
                    }
                }
            }
            return out;
        }
    }

    private RoadRoute() {
    }

    /** 도로망 자료가 없으면 null — 예전처럼 그린 직선으로 센다(「도로 없음」 이 아니다) */
    public static Plan plan(List<double[]> drawn, RoadNetwork roads) {
        if (!roads.available() || drawn.size() < 2) {
            return null;
        }
        List<Piece> pieces = new ArrayList<>();
        for (int i = 1; i < drawn.size(); i++) {
            // 찍은 점이 건물 안일 수 있다 — 가까운 큰길 위로 옮겨 그 점에서 시작한다(없으면 그대로). 안 옮기면 선이 건물 안 점에서
            // 가장 가까운 교차점까지 직선으로 뚫고 간다. 끝점·경유점이 같은 규칙이라 구간이 끊기지 않는다
            double[] a = roads.snap(drawn.get(i - 1)), b = roads.snap(drawn.get(i));
            double straight = Haversine.distanceKm(a[0], a[1], b[0], b[1]);
            var path = roads.route(a, b);
            if (path.isPresent() && path.get().km() <= straight * MAX_RATIO) {
                pieces.add(new Piece(true, path.get().points(), path.get().km(), null));
            } else {
                String reason = path.isEmpty() ? "가까운 기존 도로로 잇지 못해 새 도로"
                        : String.format(Locale.ROOT, "기존 도로가 %.1f배(%.1fkm)라 새 도로", path.get().km() / straight, path.get().km());
                pieces.add(new Piece(false, List.of(a, b), straight, reason));
            }
        }
        return new Plan(pieces);
    }

    /** 저장 문자열 — 구간마다 `R`(도로)·`N`(새 도로) 뒤에 `위도,경도` 들, 구간은 `|` 로 가른다. 도로 선은 20m 오차로 줄인다 */
    public static String encode(Plan plan) {
        if (plan == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Piece p : plan.pieces()) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            sb.append(p.road() ? 'R' : 'N');
            for (double[] pt : simplify(p.points())) {
                sb.append(String.format(Locale.ROOT, " %.5f,%.5f", pt[0], pt[1]));
            }
        }
        return sb.toString();
    }

    public static List<Piece> decode(String text) {
        List<Piece> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split("[|]")) {
            String[] tok = part.trim().split(" ");
            List<double[]> pts = new ArrayList<>();
            for (int i = 1; i < tok.length; i++) {
                int c = tok[i].indexOf(',');
                pts.add(new double[]{Double.parseDouble(tok[i].substring(0, c)), Double.parseDouble(tok[i].substring(c + 1))});
            }
            out.add(new Piece("R".equals(tok[0]), pts, 0, null));
        }
        return out;
    }

    /** 더글러스-포이커 — 양 끝은 남긴다 */
    static List<double[]> simplify(List<double[]> pts) {
        if (pts.size() <= 2) {
            return pts;
        }
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        dp(pts, 0, pts.size() - 1, keep);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i]) {
                out.add(pts.get(i));
            }
        }
        return out;
    }

    private static void dp(List<double[]> pts, int i, int j, boolean[] keep) {
        if (j <= i + 1) {
            return;
        }
        double[] a = pts.get(i), b = pts.get(j);
        double kmLng = 111.320 * Math.cos(Math.toRadians(a[0])), kmLat = 110.574;
        double bx = (b[1] - a[1]) * kmLng, by = (b[0] - a[0]) * kmLat;
        double len = Math.hypot(bx, by);
        int far = -1;
        double max = STORE_TOLERANCE_KM;
        for (int k = i + 1; k < j; k++) {
            double px = (pts.get(k)[1] - a[1]) * kmLng, py = (pts.get(k)[0] - a[0]) * kmLat;
            double d = len == 0 ? Math.hypot(px, py) : Math.abs(bx * py - by * px) / len;
            if (d > max) {
                max = d;
                far = k;
            }
        }
        if (far >= 0) {
            keep[far] = true;
            dp(pts, i, far, keep);
            dp(pts, far, j, keep);
        }
    }
}
