package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 트램·BRT 의 노선 — 그린 구간(점 i → i+1)마다 기존 도로를 따라갈지, 새 도로를 놓을지 정한다(11-12 사용자 요청, 자동 적용).
 *
 * <p>땅 위 도로를 쓰는 수단이 그린 직선 그대로 가면 다리 없는 강을 건너는 도로를 새로 놓는 셈이었다. <b>기존 도로가 직선의
 * {@value #MAX_RATIO}배 이하면 도로를 따라간다</b>(판단값 — 새 다리보다 기존 다리, 도심 도로망의 한계). 넘게 돌거나 도로를 못 찾으면 그 구간만 직선 새 도로다.
 * 새 도로의 땅 위 공사비는 KDI 지침에 km 표준이 없어 판정은 길이로만 한다(설계 문서 4).
 *
 * <p><b>단 그 직선이 강·호수·바다를 건너면 육지는 도로를 따라가고 물 위만 새 다리로 잇는다</b>(11-19 사용자 요청, {@link #acrossWater}) —
 * 전에는 4.6km 중 한강 0.9km 때문에 구간 전체가 직선 새 도로였다. 도로로 잇는 육지가 하나도 없으면 이전과 같다.
 */
public final class RoadRoute {

    /**
     * 기존 도로로 돌아가는 길이 직선의 이 배수 이하면 도로를 따라간다 — **판단값 2.0**. 처음엔 호수·산 우회와 같은 1.5 였다(11-12).
     * 직선이 물을 건너는 구간은 11-21 에 2.0 으로 넓혔고(사용자 결정 — 새 다리는 1km 에 약 451억이라 돌아가더라도 기존 다리가 낫다), 땅 위 구간도 11-22 에 같은 값으로
     * 넓혔다: 도로망이 큰길(trunk~tertiary)만 담고 자동차전용도로·이면도로를 빼 도심에서 돌아가는 길이 길고, 서울 391구간 중 26% 가 1.5배를 넘어 건물 블록을 대각선으로
     * 뚫는 「새 도로」 가 됐다(2.0배면 5%). 자료의 한계가 만든 가짜 새 도로에 가깝다. 근거가 약한 값이다 — 되돌리려면 이 상수 하나다
     */
    public static final double MAX_RATIO = 2.0;
    /**
     * 새 도로가 강·호수를 건너는 다리 — KDI 2021 세부지침 표 Ⅲ-14 일반국도 교량형식별 표준공사비, 편도 2차로(폭 11.5m) PSC Box
     * 38,601천원/m = 386.01억원/km(2019, VAT 제외 — 큰 강을 건너는 다리에 흔한 형식). 지침은 바다·큰 강 다리는 더 잡을 수 있다고 한다 — 하한이다
     */
    static final double BRIDGE_2019 = 386.01;
    public static final double BRIDGE_PER_KM = BRIDGE_2019 * MountainCost.DEFLATOR_2019;
    /** 물 위 토막이 이보다 짧으면 개울이라 다리로 치지 않는다 — 패널이 물 위 구간을 거르는 기준(50m)과 같다 */
    static final double MIN_BRIDGE_KM = 0.05;
    /** 이보다 짧은 육지 토막은 도로로 잇지 않고 이웃한 다리에 붙인다 — 도로 두 점 사이를 억지로 찾지 않는다 */
    static final double MIN_LAND_KM = 0.1;
    /** 물 토막 둘이 이만큼 안으로 맞닿으면 하나로 본다(강 하구에서 강 면과 바다 면이 겹치거나 비켜 닿는다) */
    private static final double MERGE_GAP_KM = 0.02;
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

    /** 선분 a→b 가 지나는 물(호수·강·바다) 위 토막들 {시작, 끝} — 없으면 빈 목록 */
    @FunctionalInterface
    public interface WaterOnLine {
        List<double[][]> pieces(double[] a, double[] b);
    }

    /** 물을 모르는 계획 — 1.5배를 넘으면 그 구간 전체가 직선 새 도로다 */
    public static Plan plan(List<double[]> drawn, RoadNetwork roads) {
        return plan(drawn, roads, null);
    }

    /** 도로망 자료가 없으면 null — 예전처럼 그린 직선으로 센다(「도로 없음」 이 아니다) */
    public static Plan plan(List<double[]> drawn, RoadNetwork roads, WaterOnLine water) {
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
                continue;
            }
            // 왜 새 도로인가 — 화면이 그대로 보여 준다(11-19). 「기존 도로가 N배라」 · 「가까운 기존 도로로 잇지 못해」 가 뿌리고, 물이 끼면 그 뒤에 경우를 붙인다
            String base = path.isEmpty() ? "가까운 기존 도로로 잇지 못해"
                    : String.format(Locale.ROOT, "기존 도로가 %.1f배(%.1fkm)라", path.get().km() / straight, path.get().km());
            // 직선이 물을 건너면 육지는 도로를 따라가고 물 위만 새 다리로 잇는다(11-19) — 도로로 잇는 육지가 하나도 없으면 비어 있다
            List<double[][]> bridges = water == null ? List.of() : bridges(a, b, water);
            List<Piece> mixed = bridges.isEmpty() ? List.of() : acrossWater(a, b, bridges, roads, water, base);
            if (mixed.isEmpty()) {
                String reason = base + " 새 도로" + (bridges.isEmpty() ? "" : " — 물을 건너지만 양쪽 육지도 도로로 이을 수 없어 직선 그대로");
                pieces.add(new Piece(false, List.of(a, b), straight, reason));
            } else {
                pieces.addAll(mixed);
            }
        }
        return new Plan(pieces);
    }

    /**
     * 도로로 못 이은 구간 a→b 를 「육지 – 다리 – 육지 …」 로 쪼갠다. 직선이 물 위를 지나는 토막마다 다리를 두고, 그 사이·양 끝의 육지 토막은
     * 양 끝을 큰길 위로 옮겨 도로 경로가 직선의 {@value #MAX_RATIO}배 이하면 도로를 따라간다. 도로로 못 잇는 육지는 이웃한 다리에 붙여
     * 한 토막의 새 도로로 둔다. 도로로 잇는 육지가 하나도 없으면 빈 목록 — 예전처럼 구간 전체가 직선 새 도로다(혼합이 아니다).
     */
    private static List<Piece> acrossWater(double[] a, double[] b, List<double[][]> bridges, RoadNetwork roads, WaterOnLine water, String base) {
        int n = bridges.size();
        List<Piece> out = new ArrayList<>();
        List<double[]> run = null;           // 이어 붙이는 중인 새 도로(다리 + 도로로 못 이은 육지)
        boolean runLand = false;             // 그 새 도로에 도로로 못 이은 육지가 끼었나 — 이유 문구가 갈린다
        boolean anyRoad = false;
        for (int i = 0; i <= n; i++) {
            double[] from = i == 0 ? a : bridges.get(i - 1)[1], to = i == n ? b : bridges.get(i)[0];
            // 양 끝은 큰길 위로 옮긴다 — 강둑에서 가장 가까운 길이 다리가 닿는 자리다. a·b 는 이미 옮겼다
            double[] s = i == 0 ? a : roads.snap(from), e = i == n ? b : roads.snap(to);
            double land = Haversine.distanceKm(s[0], s[1], e[0], e[1]);
            Optional<RoadNetwork.Path> path = land >= MIN_LAND_KM ? roads.route(s, e) : Optional.empty();
            if (path.isPresent() && path.get().km() <= land * MAX_RATIO) {
                if (run != null) {
                    run.set(run.size() - 1, s);           // 앞 다리는 도로 위에서 끝난다 — 조각이 끊기지 않게
                    out.add(newRoad(run, base, runLand, water));
                }
                out.add(new Piece(true, path.get().points(), path.get().km(), null));
                run = i == n ? null : new ArrayList<>(List.of(e));
                runLand = false;
                anyRoad = true;
            } else {
                if (run == null) {
                    run = new ArrayList<>(List.of(from));
                }
                run.add(to);
                runLand = true;
            }
            if (i < n) {
                run.add(bridges.get(i)[1]);               // 다리 — 물 건너편 끝까지
            }
        }
        if (!anyRoad) {
            return List.of();
        }
        if (run != null) {
            out.add(newRoad(run, base, runLand, water));
        }
        return out;
    }

    /**
     * 혼합 구간의 새 도로 — 이유는 뿌리(base) 뒤에 육지가 도로였는지·**이 조각에 실제로 물이 있는지**를 붙인다. 물이 없는 조각이 나온다 — 둑 점을 큰길로
     * 옮기면(15% 가 건너편으로 옮겨진다, 진짜 자료 6천 선 표본) 개울을 도로의 기존 다리가 건너고 새 도로는 육지만 남는다. 거기에 「물 위」 라 적으면 사실과 다르다(11-21)
     */
    private static Piece newRoad(List<double[]> points, String base, boolean withLand, WaterOnLine water) {
        double km = 0;
        boolean wet = false;
        for (int i = 1; i < points.size(); i++) {
            double[] p = points.get(i - 1), q = points.get(i);
            km += Haversine.distanceKm(p[0], p[1], q[0], q[1]);
            wet |= water.pieces(p, q).stream().anyMatch(w -> Haversine.distanceKm(w[0][0], w[0][1], w[1][0], w[1][1]) >= MIN_BRIDGE_KM);
        }
        return new Piece(false, points, km, mixedReason(base, withLand, wet));
    }

    /** 혼합 구간의 새 도로 까닭 문구 — 물이 있으면 「물 위」, 육지가 끼었으면 「도로로 못 이은 육지」, 물이 없으면 물 얘기를 안 한다 */
    static String mixedReason(String base, boolean withLand, boolean wet) {
        if (!wet) {
            return base + " 도로로 못 이은 육지만 새 도로";
        }
        return base + (withLand ? " 물 위와 도로로 못 이은 육지만 새 도로" : " 육지는 도로를 따르고 물 위만 새 다리");
    }

    /** 선 a→b 위 물 토막들 — a 에서 가까운 순으로 정렬하고, 겹치거나 맞닿은 것(강 하구의 강·바다)은 합친 뒤, 개울({@value #MIN_BRIDGE_KM}km 미만)은 뺀다 */
    private static List<double[][]> bridges(double[] a, double[] b, WaterOnLine water) {
        List<double[][]> sorted = new ArrayList<>(water.pieces(a, b));
        sorted.sort(java.util.Comparator.comparingDouble(p -> Haversine.distanceKm(a[0], a[1], p[0][0], p[0][1])));
        List<double[][]> merged = new ArrayList<>();
        for (double[][] p : sorted) {
            double[][] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && Haversine.distanceKm(a[0], a[1], p[0][0], p[0][1])
                    <= Haversine.distanceKm(a[0], a[1], last[1][0], last[1][1]) + MERGE_GAP_KM) {
                if (Haversine.distanceKm(a[0], a[1], p[1][0], p[1][1]) > Haversine.distanceKm(a[0], a[1], last[1][0], last[1][1])) {
                    last[1] = p[1];
                }
            } else {
                merged.add(new double[][]{p[0], p[1]});
            }
        }
        merged.removeIf(p -> Haversine.distanceKm(p[0][0], p[0][1], p[1][0], p[1][1]) < MIN_BRIDGE_KM);
        return merged;
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
            // 새 도로는 왜 새 도로인지도 함께 — 탭 뒤가 이유다. 구간 구분(`|`)·점 구분(공백)과 안 겹치고, 옛 저장(탭 없음)도 그대로 읽힌다
            if (p.reason() != null && !p.reason().isBlank()) {
                sb.append('\t').append(p.reason().replace('|', '/').replace('\t', ' '));
            }
        }
        return sb.toString();
    }

    public static List<Piece> decode(String text) {
        List<Piece> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String raw : text.split("[|]")) {
            Piece piece = parsePiece(raw);
            if (piece != null) {
                out.add(piece);
            }
        }
        return out;
    }

    /**
     * 저장 조각 하나 — 깨졌으면 null(건너뛴다). 좌표가 아닌 토큰이 섞였거나(`StringIndexOutOfBounds`·`NumberFormat`) 점이 하나뿐이면 선이 아니다.
     * 한 조각 때문에 시나리오 상세 조회가 통째로 실패하면 안 된다 — 노선 모양은 보조 정보고, 계산 값은 따로 저장돼 있다
     */
    private static Piece parsePiece(String raw) {
        int tab = raw.indexOf('\t');
        String part = tab < 0 ? raw : raw.substring(0, tab);
        String reason = tab < 0 ? null : raw.substring(tab + 1).trim();
        String[] tok = part.trim().split(" ");
        List<double[]> pts = new ArrayList<>();
        try {
            for (int i = 1; i < tok.length; i++) {
                int c = tok[i].indexOf(',');
                pts.add(new double[]{Double.parseDouble(tok[i].substring(0, c)), Double.parseDouble(tok[i].substring(c + 1))});
            }
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return null;
        }
        if (pts.size() < 2) {
            return null;
        }
        return new Piece("R".equals(tok[0]), pts, 0, reason == null || reason.isEmpty() ? null : reason);
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
