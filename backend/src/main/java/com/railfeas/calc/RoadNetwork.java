package com.railfeas.calc;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 큰길 중심선 — 정차역을 가장 가까운 큰길 위로 옮긴다(11-12 사용자 요청). `etl/osm_roads.py` 가 OSM 에서 받아
 * `data/build/roads.csv.gz` 에 적어 둔 간선·보조간선·집산도로다(골목·단지 통로·자동차전용도로·교량·터널 토막은 뺐다).
 *
 * <p>왜 필요한가 — 역은 반경 500m 인구가 많은 쪽으로 끌려가 **아파트 단지 한가운데**(노량진2동, 동 사이)에 놓였다. 실제 역은
 * 큰길 밑(지하철)·위(고가)나 도로 위(BRT·트램)에 있다. 한국은 OSM 에 인도가 거의 없어 중심선으로 붙인다.
 *
 * <p>{@value #MAX_SNAP_KM}km 안에 큰길이 없으면 옮기지 않는다(판단값 — 걸어서 닿는 역세권 반경 0.5km 보다 짧게).
 * **파일이 없으면 꺼진다**({@link #available()} false — 「큰길 없음」 이 아니다) — 역은 예전처럼 인구 자리에 남는다.
 * 좌표는 모두 {위도, 경도} 순서다.
 */
@Component
public class RoadNetwork {

    private static final Logger log = LoggerFactory.getLogger(RoadNetwork.class);

    /** 이보다 먼 큰길로는 옮기지 않는다 — 판단값 */
    public static final double MAX_SNAP_KM = 0.3;
    /** 격자 한 칸(도). 위도 0.005° ≈ 0.55km — 0.3km 를 찾으려면 앞뒤 한 칸씩만 보면 된다 */
    private static final double CELL = 0.005;
    private static final double KM_PER_LAT = 110.574;

    private final String file;
    /**
     * 토막 i 의 {위도1, 경도1, 위도2, 경도2} 가 4i 부터 — 도 × 10⁵ 정수(자료가 소수 5자리라 그대로 담긴다).
     * float 는 경도 127° 에서 한 칸이 1.5×10⁻⁵°(1.3m)라 길 위로 옮긴 점이 길에서 비켜났다
     */
    private static final double SCALE = 1e5;
    private int[] segs = new int[0];
    private Map<Long, int[]> grid = Map.of();

    public RoadNetwork(@Value("${app.road-file}") String file) {
        this.file = file;
    }

    /** 테스트용 — 파일 없이 선들로 만든다 */
    public static RoadNetwork of(List<double[][]> lines) {
        RoadNetwork r = new RoadNetwork("(테스트)");
        r.build(lines);
        return r;
    }

    @PostConstruct
    void load() {
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            log.warn("큰길 자료 {} 가 없어 정차역을 큰길로 옮기지 않습니다 — python etl/osm_roads.py", path.toAbsolutePath());
            return;
        }
        List<double[][]> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.US_ASCII))) {
            String row;
            while ((row = in.readLine()) != null) {
                if (row.isBlank()) {
                    continue;
                }
                String[] pts = row.trim().split(" ");
                double[][] line = new double[pts.length][];
                for (int i = 0; i < pts.length; i++) {
                    int c = pts[i].indexOf(',');
                    line[i] = new double[]{Double.parseDouble(pts[i].substring(0, c)),
                            Double.parseDouble(pts[i].substring(c + 1))};
                }
                lines.add(line);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("큰길 자료를 못 읽어 정차역을 큰길로 옮기지 않습니다 — {}", e.getMessage());
            return;
        }
        build(lines);
        log.info("큰길 토막 {}개를 읽었습니다", segs.length / 4);
    }

    private void build(List<double[][]> lines) {
        int n = 0;
        for (double[][] l : lines) {
            n += Math.max(0, l.length - 1);
        }
        int[] s = new int[n * 4];
        Map<Long, List<Integer>> cells = new HashMap<>();
        int k = 0;
        for (double[][] l : lines) {
            for (int i = 1; i < l.length; i++, k++) {
                s[k * 4] = (int) Math.round(l[i - 1][0] * SCALE);
                s[k * 4 + 1] = (int) Math.round(l[i - 1][1] * SCALE);
                s[k * 4 + 2] = (int) Math.round(l[i][0] * SCALE);
                s[k * 4 + 3] = (int) Math.round(l[i][1] * SCALE);
                long r0 = cell(Math.min(l[i - 1][0], l[i][0])), r1 = cell(Math.max(l[i - 1][0], l[i][0]));
                long c0 = cell(Math.min(l[i - 1][1], l[i][1])), c1 = cell(Math.max(l[i - 1][1], l[i][1]));
                for (long r = r0; r <= r1; r++) {
                    for (long c = c0; c <= c1; c++) {
                        cells.computeIfAbsent(key(r, c), x -> new ArrayList<>()).add(k);
                    }
                }
            }
        }
        Map<Long, int[]> g = new HashMap<>(cells.size() * 2);
        cells.forEach((key, list) -> g.put(key, list.stream().mapToInt(Integer::intValue).toArray()));
        this.segs = s;
        this.grid = g;
    }

    public boolean available() {
        return segs.length > 0;
    }

    /** 가장 가까운 큰길 위의 점. maxKm 안에 없으면 빈 값 */
    public Optional<double[]> nearest(double lat, double lng, double maxKm) {
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

    private static long cell(double deg) {
        return (long) Math.floor(deg / CELL);
    }

    private static long key(long row, long col) {
        return row * 1_000_000L + col;
    }
}
