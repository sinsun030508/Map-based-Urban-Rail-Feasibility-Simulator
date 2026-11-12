package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 큰길 도로망 — `etl/osm_roads.py` 가 OSM 에서 받아 `data/build/road_graph.csv.gz` 에 적은 간선·보조간선·집산도로와 그 진출입로·다리·터널
 * (골목·단지 통로·자동차전용도로는 뺐다). 두 가지에 쓴다(둘 다 11-12 사용자 요청).
 *
 * <p><b>① 정차역을 큰길 위로</b>({@link #snap}) — 역이 아파트 단지 한가운데(노량진2동, 동 사이)에 놓였다. {@value #MAX_SNAP_KM}km 안의 가장
 * 가까운 큰길 「선분 위」 점으로 옮긴다(판단값). <b>다리·터널·진출입로 위로는 옮기지 않는다</b> — 한강 다리 위·산 터널 속에 역이 갈 수 없다.
 * 한국 OSM 엔 인도가 거의 없어 중심선에 붙인다.
 *
 * <p><b>② 기존 도로로 가는 길</b>({@link #route}) — 트램·BRT 는 이미 있는 도로·다리를 따라간다. 양 끝에서 {@value #ENTRY_KM}km 안의 가장 가까운
 * 교차점끼리 A*(거리 = 도로 중심선 길이, 어림 = 직선 거리)로 잇는다. 방향(일방통행)·회전은 보지 않는다.
 *
 * <p><b>파일이 없으면 꺼진다</b>({@link #available()} false — 「큰길 없음」 이 아니다). 좌표는 모두 {위도, 경도} 순서다.
 */
@Component
public class RoadNetwork {

    private static final Logger log = LoggerFactory.getLogger(RoadNetwork.class);

    /** 이보다 먼 큰길로는 역을 옮기지 않는다 — 판단값 */
    public static final double MAX_SNAP_KM = 0.3;
    /** 그린 점에서 이보다 먼 교차점으로는 들어가지 않는다 — 판단값(도로가 없는 곳을 「길 있음」 으로 치지 않게) */
    public static final double ENTRY_KM = 1.0;
    /** 다리 · 터널 · 진출입로 · 집산도로 표시(`osm_roads.py` 와 같다) */
    public static final int BRIDGE = 1, TUNNEL = 2, LINK = 4, MINOR = 8;
    /** 역을 둘 수 없는 간선 — 다리·터널·진출입로 */
    private static final int NO_STATION = BRIDGE | TUNNEL | LINK;
    /** A* 가 펼칠 교차점 수 한도 — 넘으면 「못 찾음」(서울~부산 한 구간도 이 안에서 끝난다) */
    private static final int MAX_EXPAND = 3_000_000;
    /** 격자 한 칸(도). 위도 0.005° ≈ 0.55km */
    private static final double CELL = 0.005;
    private static final double KM_PER_LAT = 110.574;
    /**
     * 좌표는 도 × 10⁵ 정수(자료가 소수 5자리라 그대로 담긴다). float 는 경도 127° 에서 한 칸이 1.5×10⁻⁵°(1.3m)라 길 위로 옮긴 점이
     * 길에서 비켜났다
     */
    private static final double SCALE = 1e5;

    /** 읽은 간선 하나(테스트가 만든다) */
    public record Edge(int a, int b, int flags, double[][] coords) {
    }

    /** 기존 도로로 가는 길 — 양 끝은 물은 점 그대로 */
    public record Path(List<double[]> points, double km) {
    }

    private final String file;

    // 역 붙이기 — 다리·터널·진출입로가 아닌 간선의 토막 {위도1, 경도1, 위도2, 경도2}. 집산도로 토막은 따로 둔다(보조간선 이상을 먼저 찾는다)
    private int[] segs = new int[0];
    private Map<Long, int[]> segGrid = Map.of();
    private Map<Long, int[]> minorGrid = Map.of();

    // 길 찾기 — 간선 e 의 좌표는 coords[2·start[e] …] , 교차점 v 의 간선은 adjEdge[adjStart[v] … adjStart[v+1])
    private int[] edgeA = new int[0], edgeB = new int[0], edgeStart = new int[1];
    private int[] coords = new int[0];
    private double[] edgeKm = new double[0];
    private double[] nodeLat = new double[0], nodeLng = new double[0];
    private int[] adjStart = new int[1], adjEdge = new int[0];
    private Map<Long, int[]> nodeGrid = Map.of();

    public RoadNetwork(@Value("${app.road-file}") String file) {
        this.file = file;
    }

    /** 테스트용 — 서로 이어지지 않은 땅 위 큰길들로 만든다 */
    public static RoadNetwork of(List<double[][]> lines) {
        List<Edge> edges = new ArrayList<>();
        for (double[][] l : lines) {
            edges.add(new Edge(edges.size() * 2, edges.size() * 2 + 1, 0, l));
        }
        return ofEdges(edges);
    }

    /** 테스트용 — 간선들로 만든다 */
    public static RoadNetwork ofEdges(List<Edge> edges) {
        RoadNetwork r = new RoadNetwork("(테스트)");
        r.build(edges);
        return r;
    }

    @PostConstruct
    void load() {
        java.nio.file.Path path = java.nio.file.Path.of(file);
        if (!Files.exists(path)) {
            log.warn("큰길 도로망 {} 이 없어 역을 큰길로 옮기지 않고 트램·BRT 도 직선으로 셉니다 — python etl/osm_roads.py",
                    path.toAbsolutePath());
            return;
        }
        List<Edge> edges = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.US_ASCII))) {
            String row;
            while ((row = in.readLine()) != null) {
                if (row.isBlank()) {
                    continue;
                }
                String[] parts = row.trim().split(" ");
                double[][] line = new double[parts.length - 3][];
                for (int i = 3; i < parts.length; i++) {
                    int c = parts[i].indexOf(',');
                    line[i - 3] = new double[]{Double.parseDouble(parts[i].substring(0, c)),
                            Double.parseDouble(parts[i].substring(c + 1))};
                }
                edges.add(new Edge(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), line));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("큰길 도로망을 못 읽어 꺼 둡니다 — {}", e.getMessage());
            return;
        }
        build(edges);
        log.info("큰길 도로망 — 교차점 {}개 · 간선 {}개 · 역 붙일 토막 {}개", nodeLat.length, edgeA.length, segs.length / 4);
    }

    private void build(List<Edge> edges) {
        int nodes = 0, points = 0, pieces = 0;
        for (Edge e : edges) {
            nodes = Math.max(nodes, Math.max(e.a(), e.b()) + 1);
            points += e.coords().length;
            if ((e.flags() & NO_STATION) == 0) {
                pieces += e.coords().length - 1;
            }
        }
        int m = edges.size();
        int[] ea = new int[m], eb = new int[m], start = new int[m + 1];
        int[] xy = new int[points * 2];
        double[] km = new double[m];
        double[] lat = new double[nodes], lng = new double[nodes];
        int[] degree = new int[nodes + 1];
        int[] s = new int[pieces * 4];
        Map<Long, List<Integer>> segCells = new HashMap<>();
        Map<Long, List<Integer>> minorCells = new HashMap<>();
        int p = 0, k = 0;
        for (int e = 0; e < m; e++) {
            Edge in = edges.get(e);
            double[][] c = in.coords();
            ea[e] = in.a();
            eb[e] = in.b();
            start[e] = p;
            for (int i = 0; i < c.length; i++, p++) {
                xy[p * 2] = (int) Math.round(c[i][0] * SCALE);
                xy[p * 2 + 1] = (int) Math.round(c[i][1] * SCALE);
                if (i > 0) {
                    km[e] += Haversine.distanceKm(c[i - 1][0], c[i - 1][1], c[i][0], c[i][1]);
                    if ((in.flags() & NO_STATION) == 0) {
                        System.arraycopy(new int[]{xy[(p - 1) * 2], xy[(p - 1) * 2 + 1], xy[p * 2], xy[p * 2 + 1]}, 0, s, k * 4, 4);
                        Map<Long, List<Integer>> target = (in.flags() & MINOR) == 0 ? segCells : minorCells;
                        for (long r = cell(Math.min(c[i - 1][0], c[i][0])); r <= cell(Math.max(c[i - 1][0], c[i][0])); r++) {
                            for (long q = cell(Math.min(c[i - 1][1], c[i][1])); q <= cell(Math.max(c[i - 1][1], c[i][1])); q++) {
                                target.computeIfAbsent(key(r, q), x -> new ArrayList<>()).add(k);
                            }
                        }
                        k++;
                    }
                }
            }
            lat[in.a()] = c[0][0];
            lng[in.a()] = c[0][1];
            lat[in.b()] = c[c.length - 1][0];
            lng[in.b()] = c[c.length - 1][1];
            degree[in.a() + 1]++;
            degree[in.b() + 1]++;
        }
        start[m] = p;
        // 교차점별 간선 목록(CSR)
        for (int v = 0; v < nodes; v++) {
            degree[v + 1] += degree[v];
        }
        int[] fill = Arrays.copyOf(degree, nodes + 1);
        int[] adj = new int[m * 2];
        for (int e = 0; e < m; e++) {
            adj[fill[ea[e]]++] = e;
            adj[fill[eb[e]]++] = e;
        }
        Map<Long, List<Integer>> nodeCells = new HashMap<>();
        for (int v = 0; v < nodes; v++) {
            if (degree[v + 1] > degree[v]) {
                nodeCells.computeIfAbsent(key(cell(lat[v]), cell(lng[v])), x -> new ArrayList<>()).add(v);
            }
        }
        this.segs = s;
        this.segGrid = freeze(segCells);
        this.minorGrid = freeze(minorCells);
        this.edgeA = ea;
        this.edgeB = eb;
        this.edgeStart = start;
        this.coords = xy;
        this.edgeKm = km;
        this.nodeLat = lat;
        this.nodeLng = lng;
        this.adjStart = degree;
        this.adjEdge = adj;
        this.nodeGrid = freeze(nodeCells);
    }

    private static Map<Long, int[]> freeze(Map<Long, List<Integer>> cells) {
        Map<Long, int[]> g = new HashMap<>(cells.size() * 2);
        cells.forEach((key, list) -> g.put(key, list.stream().mapToInt(Integer::intValue).toArray()));
        return g;
    }

    public boolean available() {
        return edgeA.length > 0;
    }

    /**
     * 가장 가까운 (역을 둘 수 있는) 큰길 위의 점 — <b>보조간선 이상을 먼저</b> 찾고, maxKm 안에 없을 때만 집산도로(11-12 사용자 결정 —
     * 집산도로엔 낮은 건물 사이 2차로 길이 섞여 있어 노량진2동역이 그런 길 모퉁이에 붙었다). 둘 다 없으면 빈 값
     */
    public Optional<double[]> nearest(double lat, double lng, double maxKm) {
        Optional<double[]> major = nearestIn(segGrid, lat, lng, maxKm);
        return major.isPresent() ? major : nearestIn(minorGrid, lat, lng, maxKm);
    }

    private Optional<double[]> nearestIn(Map<Long, int[]> grid, double lat, double lng, double maxKm) {
        double kmPerLng = 111.320 * Math.cos(Math.toRadians(lat));
        int reachLat = (int) Math.ceil(maxKm / KM_PER_LAT / CELL);
        int reachLng = (int) Math.ceil(maxKm / kmPerLng / CELL);
        long r0 = cell(lat), c0 = cell(lng);
        double best = maxKm;
        double[] found = null;
        for (long r = r0 - reachLat; r <= r0 + reachLat; r++) {
            for (long c = c0 - reachLng; c <= c0 + reachLng; c++) {
                int[] ids = grid.get(key(r, c));
                if (ids == null) {
                    continue;
                }
                for (int id : ids) {
                    // 물음점을 원점으로 한 km 평면 — 0.3km 안쪽이라 평면 근사로 충분하다
                    double ay = (segs[id * 4] / SCALE - lat) * KM_PER_LAT, ax = (segs[id * 4 + 1] / SCALE - lng) * kmPerLng;
                    double by = (segs[id * 4 + 2] / SCALE - lat) * KM_PER_LAT, bx = (segs[id * 4 + 3] / SCALE - lng) * kmPerLng;
                    double dx = bx - ax, dy = by - ay;
                    double len2 = dx * dx + dy * dy;
                    double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
                    double px = ax + t * dx, py = ay + t * dy;
                    double d = Math.hypot(px, py);
                    if (d <= best) {
                        best = d;
                        found = new double[]{lat + py / KM_PER_LAT, lng + px / kmPerLng};
                    }
                }
            }
        }
        return Optional.ofNullable(found);
    }

    /** 큰길 위로 옮긴 자리 — {@value #MAX_SNAP_KM}km 안에 없거나 자료가 없으면 그대로 */
    public double[] snap(double[] point) {
        return nearest(point[0], point[1], MAX_SNAP_KM).orElse(point);
    }

    /** {@value #ENTRY_KM}km 안의 가장 가까운 교차점. 없으면 -1 */
    int nearestNode(double lat, double lng) {
        double kmPerLng = 111.320 * Math.cos(Math.toRadians(lat));
        int reachLat = (int) Math.ceil(ENTRY_KM / KM_PER_LAT / CELL);
        int reachLng = (int) Math.ceil(ENTRY_KM / kmPerLng / CELL);
        long r0 = cell(lat), c0 = cell(lng);
        double best = ENTRY_KM;
        int found = -1;
        for (long r = r0 - reachLat; r <= r0 + reachLat; r++) {
            for (long c = c0 - reachLng; c <= c0 + reachLng; c++) {
                int[] ids = nodeGrid.get(key(r, c));
                if (ids == null) {
                    continue;
                }
                for (int v : ids) {
                    double d = Haversine.distanceKm(lat, lng, nodeLat[v], nodeLng[v]);
                    if (d <= best) {
                        best = d;
                        found = v;
                    }
                }
            }
        }
        return found;
    }

    /** 기존 도로로 from → to. 양 끝에서 {@value #ENTRY_KM}km 안에 교차점이 없거나 이어지지 않으면 빈 값 */
    public Optional<Path> route(double[] from, double[] to) {
        if (!available()) {
            return Optional.empty();
        }
        int s = nearestNode(from[0], from[1]), t = nearestNode(to[0], to[1]);
        if (s < 0 || t < 0) {
            return Optional.empty();
        }
        int n = nodeLat.length;
        double[] dist = new double[n];
        Arrays.fill(dist, Double.MAX_VALUE);
        int[] via = new int[n];        // 그 교차점에 들어온 간선
        Arrays.fill(via, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
        dist[s] = 0;
        open.add(new double[]{Haversine.distanceKm(nodeLat[s], nodeLng[s], nodeLat[t], nodeLng[t]), s});
        int expanded = 0;
        boolean[] done = new boolean[n];
        while (!open.isEmpty()) {
            int u = (int) open.poll()[1];
            if (done[u]) {
                continue;
            }
            if (u == t) {
                break;
            }
            done[u] = true;
            if (++expanded > MAX_EXPAND) {
                return Optional.empty();
            }
            for (int i = adjStart[u]; i < adjStart[u + 1]; i++) {
                int e = adjEdge[i];
                int w = edgeA[e] == u ? edgeB[e] : edgeA[e];
                double nd = dist[u] + edgeKm[e];
                if (nd < dist[w]) {
                    dist[w] = nd;
                    via[w] = e;
                    open.add(new double[]{nd + Haversine.distanceKm(nodeLat[w], nodeLng[w], nodeLat[t], nodeLng[t]), w});
                }
            }
        }
        if (dist[t] == Double.MAX_VALUE) {
            return Optional.empty();
        }
        // t 에서 거꾸로 간선을 모아 s → t 순서의 좌표로 편다
        List<Integer> chain = new ArrayList<>();
        for (int v = t; v != s; ) {
            int e = via[v];
            chain.add(e);
            v = edgeA[e] == v ? edgeB[e] : edgeA[e];
        }
        List<double[]> pts = new ArrayList<>();
        pts.add(from);
        addIfNew(pts, new double[]{nodeLat[s], nodeLng[s]});
        int at = s;
        for (int i = chain.size() - 1; i >= 0; i--) {
            int e = chain.get(i);
            boolean forward = edgeA[e] == at;
            int a = edgeStart[e], b = edgeStart[e + 1];
            for (int j = 0; j < b - a; j++) {
                int q = forward ? a + j : b - 1 - j;
                addIfNew(pts, new double[]{coords[q * 2] / SCALE, coords[q * 2 + 1] / SCALE});
            }
            at = forward ? edgeB[e] : edgeA[e];
        }
        addIfNew(pts, to);
        double km = 0;
        for (int i = 1; i < pts.size(); i++) {
            km += Haversine.distanceKm(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
        }
        return Optional.of(new Path(pts, km));
    }

    private static void addIfNew(List<double[]> pts, double[] p) {
        double[] last = pts.get(pts.size() - 1);
        if (Math.abs(last[0] - p[0]) > 1e-9 || Math.abs(last[1] - p[1]) > 1e-9) {
            pts.add(p);
        }
    }

    private static long cell(double deg) {
        return (long) Math.floor(deg / CELL);
    }

    private static long key(long row, long col) {
        return row * 1_000_000L + col;
    }
}
