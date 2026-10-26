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
 * **강은 없다.** 한강·낙동강은 돌아갈 수 없고 건너야 한다. 넓이 1km² 이상의 호수·저수지만
 * 있고, 섬(안쪽 경계)은 빼고 바깥 경계로 통째로 막는다.
 *
 * 좌표는 모두 {위도, 경도} 순서다(GeoJSON 은 경도가 먼저라 읽을 때 뒤집는다).
 */
@Component
public class WaterBodies {

    private static final Logger log = LoggerFactory.getLogger(WaterBodies.class);

    /** 한 호수. rings 는 바깥 경계들(여러 조각일 수 있다), 각 점은 {위도, 경도} */
    public record Body(String name, List<double[][]> rings,
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
                add(f.get("properties").path("name").asText(""), rings);
            }
        } catch (Exception e) {
            throw new IllegalStateException(file + " 를 읽지 못했습니다", e);
        }
        log.info("호수·저수지 {}개 적재", bodies.size());
    }

    /** 테스트와 적재가 함께 쓴다. 이름이 없으면 「이름 없는 저수지」 */
    void add(String name, List<double[][]> rings) {
        double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
        for (double[][] ring : rings) {
            for (double[] p : ring) {
                minLat = Math.min(minLat, p[0]);
                maxLat = Math.max(maxLat, p[0]);
                minLng = Math.min(minLng, p[1]);
                maxLng = Math.max(maxLng, p[1]);
            }
        }
        bodies.add(new Body(name == null || name.isBlank() ? "이름 없는 저수지" : name,
                rings, minLat, maxLat, minLng, maxLng));
    }

    /** 적재된 호수 수 — 실제 파일을 다 읽었는지 테스트에서 본다 */
    int size() {
        return bodies.size();
    }

    /** 선분 a→b 가 지나는 호수들 */
    public List<Body> crossing(double[] a, double[] b) {
        return bodies.stream().filter(w -> w.crossedBy(a, b)).toList();
    }

    /** 상자와 겹치는 호수들 — 우회 길을 찾을 때 그 둘레의 물을 모두 막는 데 쓴다 */
    public List<Body> within(double minLat, double maxLat, double minLng, double maxLng) {
        return bodies.stream().filter(w -> w.overlaps(minLat, maxLat, minLng, maxLng)).toList();
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
