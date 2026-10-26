package com.railfeas.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 호수·저수지 영역. `etl/osm_water.py` 가 OSM 에서 받아 `data/build/water_bodies.geojson` 에
 * 적어 둔 것을 읽는다(요청할 때 외부 API 를 부르지 않는다 — `KtxTravelTime` 과 같은 이유).
 *
 * 종류가 둘이다. **호수(`lake`)** 는 우회 판정에 쓰고, **강(`river`)** 은 우회하지 않는다 —
 * 한강·낙동강은 건너야 한다. 대신 둘 다 **물 위 구간 길이**({@link #onWater})를 잰다. 교량
 * 할증을 못 넣은 몫이 얼마나 되는지 보여 주려는 것이다. 섬(안쪽 경계)은 빼고 바깥 경계로 본다.
 *
 * 좌표는 모두 {위도, 경도} 순서다(GeoJSON 은 경도가 먼저라 읽을 때 뒤집는다).
 */
@Component
public class WaterBodies {

    private static final Logger log = LoggerFactory.getLogger(WaterBodies.class);

    public static final String LAKE = "lake";
    public static final String RIVER = "river";

    /** 물 위를 지나는 길이 — 같은 이름은 더한다 */
    public record Span(String name, String kind, double km) {
    }

    /** 한 물. rings 는 바깥 경계들(여러 조각일 수 있다), 각 점은 {위도, 경도} */
    public record Body(String name, String kind, List<double[][]> rings,
                       double minLat, double maxLat, double minLng, double maxLng) {

        boolean overlaps(double lat1, double lat2, double lng1, double lng2) {
            return Math.max(lat1, lat2) >= minLat && Math.min(lat1, lat2) <= maxLat
                    && Math.max(lng1, lng2) >= minLng && Math.min(lng1, lng2) <= maxLng;
        }

        /** 짝홀 규칙 — 경계를 몇 번 넘는지 센다 */
        boolean contains(double lat, double lng) {
            for (double[][] ring : rings) {
                boolean in = false;
                for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
                    double yi = ring[i][0], xi = ring[i][1], yj = ring[j][0], xj = ring[j][1];
                    if ((yi > lat) != (yj > lat)
                            && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                        in = !in;
                    }
                }
                if (in) {
                    return true;
                }
            }
            return false;
        }

        /** 선분이 이 호수를 지나는가 — 끝점이 물 안이거나, 경계와 한 번이라도 만난다 */
        boolean crossedBy(double[] a, double[] b) {
            if (!overlaps(a[0], b[0], a[1], b[1])) {
                return false;
            }
            if (contains(a[0], a[1]) || contains(b[0], b[1])) {
                return true;
            }
            for (double[][] ring : rings) {
                for (int i = 1; i < ring.length; i++) {
                    if (segmentsCross(a, b, ring[i - 1], ring[i])) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private final Path file;
    private final List<Body> bodies = new ArrayList<>();

    public WaterBodies(@Value("${app.water-file}") String file) {
        this.file = Path.of(file);
    }

    @PostConstruct
    void load() {
        if (!Files.exists(file)) {
            // 없으면 우회 제안만 빠진다 — 기동을 막지 않는다
            log.warn("{} 없음 — 호수·저수지 우회 제안이 비활성화된다", file);
            return;
        }
        try {
            JsonNode fc = new ObjectMapper().readTree(file.toFile());
            for (JsonNode f : fc.get("features")) {
                List<double[][]> rings = new ArrayList<>();
                for (JsonNode polygon : f.get("geometry").get("coordinates")) {
                    JsonNode outer = polygon.get(0);
                    double[][] ring = new double[outer.size()][];
                    for (int i = 0; i < outer.size(); i++) {
                        // GeoJSON 은 [경도, 위도]
                        ring[i] = new double[]{outer.get(i).get(1).asDouble(), outer.get(i).get(0).asDouble()};
                    }
                    rings.add(ring);
                }
                JsonNode props = f.get("properties");
                add(props.path("name").asText(""), props.path("kind").asText(LAKE), rings);
            }
        } catch (Exception e) {
            throw new IllegalStateException(file + " 를 읽지 못했습니다", e);
        }
        log.info("호수·저수지 {}개, 강 수면 {}개 적재",
                bodies.stream().filter(b -> LAKE.equals(b.kind())).count(),
                bodies.stream().filter(b -> RIVER.equals(b.kind())).count());
    }

    /** 호수 하나 — 테스트가 쓴다 */
    void add(String name, List<double[][]> rings) {
        add(name, LAKE, rings);
    }

    /** 테스트와 적재가 함께 쓴다. 이름이 없으면 「이름 없는 저수지」·「이름 없는 강」 */
    void add(String name, String kind, List<double[][]> rings) {
        double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
        for (double[][] ring : rings) {
            for (double[] p : ring) {
                minLat = Math.min(minLat, p[0]);
                maxLat = Math.max(maxLat, p[0]);
                minLng = Math.min(minLng, p[1]);
                maxLng = Math.max(maxLng, p[1]);
            }
        }
        String label = name == null || name.isBlank()
                ? (RIVER.equals(kind) ? "이름 없는 강" : "이름 없는 저수지") : name;
        bodies.add(new Body(label, kind, rings, minLat, maxLat, minLng, maxLng));
    }

    /** 적재된 호수 수 — 실제 파일을 다 읽었는지 테스트에서 본다 */
    int size() {
        return bodies.size();
    }

    /** 선분 a→b 가 지나는 **호수들** — 강은 돌아가지 않으므로 넣지 않는다 */
    public List<Body> crossing(double[] a, double[] b) {
        return bodies.stream().filter(w -> LAKE.equals(w.kind()) && w.crossedBy(a, b)).toList();
    }

    /**
     * 상자와 겹치는 **호수들** — 우회 길을 찾을 때 그 둘레의 물을 막는 데 쓴다. 강까지 막으면
     * 강으로 이어진 호수(의암호 위쪽이 소양강이다)는 돌아갈 길이 아예 없어진다.
     */
    public List<Body> within(double minLat, double maxLat, double minLng, double maxLng) {
        return bodies.stream()
                .filter(w -> LAKE.equals(w.kind()) && w.overlaps(minLat, maxLat, minLng, maxLng))
                .toList();
    }

    /**
     * 노선이 물 위를 지나는 길이 — 이름별로 더해 긴 순서로. 호수와 강 모두다.
     *
     * 구간마다 경계와 만나는 점으로 잘게 나누고, 조각의 가운데 점이 물 안이면 그 조각을 센다.
     * 짧은 구간이라 위경도 비율을 그대로 길이 비율로 쓴다(구간 길이는 Haversine).
     */
    public List<Span> onWater(List<double[]> route) {
        java.util.Map<String, double[]> sum = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> kindOf = new java.util.HashMap<>();
        for (int i = 1; i < route.size(); i++) {
            double[] a = route.get(i - 1), b = route.get(i);
            double segKm = com.railfeas.geo.Haversine.distanceKm(a[0], a[1], b[0], b[1]);
            for (Body w : bodies) {
                if (!w.overlaps(a[0], b[0], a[1], b[1])) {
                    continue;
                }
                List<Double> ts = new ArrayList<>(List.of(0.0, 1.0));
                for (double[][] ring : w.rings()) {
                    for (int k = 1; k < ring.length; k++) {
                        Double t = crossingAt(a, b, ring[k - 1], ring[k]);
                        if (t != null) {
                            ts.add(t);
                        }
                    }
                }
                ts.sort(Double::compare);
                double inside = 0;
                for (int k = 1; k < ts.size(); k++) {
                    double t0 = ts.get(k - 1), t1 = ts.get(k), tm = (t0 + t1) / 2;
                    if (t1 > t0 && w.contains(a[0] + (b[0] - a[0]) * tm, a[1] + (b[1] - a[1]) * tm)) {
                        inside += t1 - t0;
                    }
                }
                if (inside > 0) {
                    sum.computeIfAbsent(w.name(), k -> new double[1])[0] += inside * segKm;
                    kindOf.putIfAbsent(w.name(), w.kind());
                }
            }
        }
        return sum.entrySet().stream()
                .map(e -> new Span(e.getKey(), kindOf.get(e.getKey()), e.getValue()[0]))
                .sorted((x, y) -> Double.compare(y.km(), x.km()))
                .toList();
    }

    /** 선분 p1→p2 위에서 q1→q2 와 만나는 자리(0~1). 안 만나면 null */
    private static Double crossingAt(double[] p1, double[] p2, double[] q1, double[] q2) {
        double rx = p2[0] - p1[0], ry = p2[1] - p1[1];
        double sx = q2[0] - q1[0], sy = q2[1] - q1[1];
        double den = rx * sy - ry * sx;
        if (den == 0) {
            return null;
        }
        double qpx = q1[0] - p1[0], qpy = q1[1] - p1[1];
        double t = (qpx * sy - qpy * sx) / den;
        double u = (qpx * ry - qpy * rx) / den;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? t : null;
    }

    static boolean segmentsCross(double[] p1, double[] p2, double[] q1, double[] q2) {
        double d1 = cross(q1, q2, p1), d2 = cross(q1, q2, p2);
        double d3 = cross(p1, p2, q1), d4 = cross(p1, p2, q2);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0));
    }

    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }
}
