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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 해안선 — 점이 바다인지, 선이 바다 위를 몇 km 지나는지(11-18). 새 도로가 바다를 건너는 다리값을 매기려고 쓴다(`CostCalculator.roadPart`).
 *
 * <p><b>서쪽 바다로 쏜 선이 해안선과 만나는 횟수의 짝홀</b>로 가른다 — 홀수면 육지, 짝수면 바다. 가까운 해안선 선분의 방향(육지가 왼쪽)으로 가르면
 * 오목한 만에서 틀리고, 지형 타일(DEM)로는 연안 바다와 간척지가 둘 다 0m 라 안 갈린다(research-log 「바다를 DEM 으로 가를 수 없다」).
 * 섬은 닫힌 해안선이라 지나는 선이 횟수를 2씩 늘릴 뿐이다 — 칸 밖 섬이 빠져도 그 섬 안의 점만 틀린다(수집기가 서해·동해 섬까지 받는다).
 * 반개구간(`(a.lat <= lat) != (b.lat <= lat)`)으로 세어, 점이 해안선 꼭짓점과 같은 위도여도 두 번 세지 않는다.
 *
 * <p><b>자료 범위 밖은 바다로 세지 않는다</b> — 북위 38.7° 위(북한)나 33° 아래는 자료가 없어 횟수가 0(짝수)이라 바다처럼 읽힌다.
 * <b>파일이 없으면 꺼진다</b>({@link #available()} false — 육지도 바다도 아니다). 좌표는 모두 {위도, 경도} 순서다.
 */
@Component
public class Coastline {

    private static final Logger log = LoggerFactory.getLogger(Coastline.class);

    /** `WaterBodies.Span` 의 종류·이름 — 프론트는 종류를 호수 우회 안내에서만 가려 쓴다(`lake`), 나머지는 이름과 길이만 적는다 */
    public static final String SEA = "sea";
    public static final String SEA_NAME = "바다";

    /** 수집기가 받은 칸이 덮는 범위 — 그 밖은 자료가 없다 */
    static final double MIN_LAT = 33.0, MAX_LAT = 38.7, MIN_LNG = 124.0, MAX_LNG = 132.0;
    /** 서쪽 끝 — 중국 산둥 반도 동쪽 끝(122.7°)보다 동쪽이라 어느 위도에서나 바다다. 선을 여기까지 쏜다 */
    private static final double OPEN_SEA_LNG = 123.0;
    private static final int PER_DEGREE = 100;           // 위도 띠 0.01° ≈ 1.1km

    private final String file;
    private double[] seg = new double[0];                // 선분 i 는 {위도1, 경도1, 위도2, 경도2} = seg[4i … 4i+3]
    private Map<Integer, int[]> latBand = Map.of();      // 위도 띠 → 그 띠에 걸치는 선분 번호

    public Coastline(@Value("${app.coast-file}") String file) {
        this.file = file;
    }

    /** 테스트용 — way(꼭짓점 줄)들로 만든다. 닫힌 way 면 마지막 점이 첫 점과 같다 */
    public static Coastline of(List<double[][]> ways) {
        Coastline c = new Coastline("(테스트)");
        c.build(ways);
        return c;
    }

    @PostConstruct
    void load() {
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            log.warn("해안선 {} 이 없어 바다 위 새 도로의 다리값을 매기지 않습니다 — python etl/osm_coast.py", path.toAbsolutePath());
            return;
        }
        List<double[][]> ways = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.US_ASCII))) {
            String row;
            while ((row = in.readLine()) != null) {
                if (row.isBlank()) {
                    continue;
                }
                String[] parts = row.trim().split(" ");
                double[][] line = new double[parts.length][];
                for (int i = 0; i < parts.length; i++) {
                    int c = parts[i].indexOf(',');
                    line[i] = new double[]{Double.parseDouble(parts[i].substring(0, c)), Double.parseDouble(parts[i].substring(c + 1))};
                }
                ways.add(line);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("해안선을 못 읽어 꺼 둡니다 — {}", e.getMessage());
            return;
        }
        build(ways);
        log.info("해안선 — way {}개 · 선분 {}개", ways.size(), seg.length / 4);
    }

    private void build(List<double[][]> ways) {
        int n = 0;
        for (double[][] w : ways) {
            n += Math.max(0, w.length - 1);
        }
        double[] s = new double[4 * n];
        int k = 0;
        for (double[][] w : ways) {
            for (int i = 1; i < w.length; i++) {
                s[k++] = w[i - 1][0];
                s[k++] = w[i - 1][1];
                s[k++] = w[i][0];
                s[k++] = w[i][1];
            }
        }
        Map<Integer, List<Integer>> tmp = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int lo = band(Math.min(s[4 * i], s[4 * i + 2])), hi = band(Math.max(s[4 * i], s[4 * i + 2]));
            for (int b = lo; b <= hi; b++) {
                tmp.computeIfAbsent(b, x -> new ArrayList<>()).add(i);
            }
        }
        Map<Integer, int[]> idx = new HashMap<>();
        tmp.forEach((b, l) -> idx.put(b, l.stream().mapToInt(Integer::intValue).toArray()));
        this.seg = s;
        this.latBand = idx;
    }

    private static int band(double lat) {
        return (int) Math.floor(lat * PER_DEGREE);
    }

    /** 해안선 자료가 있다 — 없으면 바다 위 길이를 모른다(0 이 아니다) */
    public boolean available() {
        return seg.length > 0;
    }

    /** 이 점이 바다인가. 자료 범위 밖이거나 자료가 없으면 false(바다로 세지 않는다) */
    public boolean isSea(double lat, double lng) {
        if (!available() || lat < MIN_LAT || lat > MAX_LAT || lng < MIN_LNG || lng > MAX_LNG) {
            return false;
        }
        int[] cand = latBand.get(band(lat));
        if (cand == null) {
            return true;                                 // 이 위도에 해안선이 하나도 없다 — 서쪽 끝까지 바다뿐이다
        }
        int crossings = 0;
        for (int i : cand) {
            double a0 = seg[4 * i], a1 = seg[4 * i + 1], b0 = seg[4 * i + 2], b1 = seg[4 * i + 3];
            if ((a0 <= lat) != (b0 <= lat)) {
                double at = a1 + (lat - a0) * (b1 - a1) / (b0 - a0);   // 이 선분이 위도 lat 를 지나는 경도
                if (at < lng && at > OPEN_SEA_LNG) {
                    crossings++;
                }
            }
        }
        return crossings % 2 == 0;
    }

    /** 선분 a→b 에서 바다인 토막들 — 해안선과 만나는 자리마다 나눠 가운데 점이 바다인 토막만 남긴다 */
    public List<double[][]> seaPieces(double[] a, double[] b) {
        List<double[][]> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        List<Double> ts = new ArrayList<>(List.of(0.0, 1.0));
        double lo = Math.min(a[0], b[0]), hi = Math.max(a[0], b[0]);
        double minLng = Math.min(a[1], b[1]), maxLng = Math.max(a[1], b[1]);
        java.util.BitSet seen = new java.util.BitSet();
        for (int bd = band(lo); bd <= band(hi); bd++) {
            int[] cand = latBand.get(bd);
            if (cand == null) {
                continue;
            }
            for (int i : cand) {
                if (seen.get(i)) {
                    continue;
                }
                seen.set(i);
                double q1x = seg[4 * i], q1y = seg[4 * i + 1], q2x = seg[4 * i + 2], q2y = seg[4 * i + 3];
                if (Math.max(q1y, q2y) < minLng || Math.min(q1y, q2y) > maxLng) {
                    continue;
                }
                Double t = crossingAt(a, b, q1x, q1y, q2x, q2y);
                if (t != null) {
                    ts.add(t);
                }
            }
        }
        ts.sort(Double::compare);
        for (int k = 1; k < ts.size(); k++) {
            double t0 = ts.get(k - 1), t1 = ts.get(k);
            if (t1 - t0 < 1e-9) {
                continue;
            }
            double tm = (t0 + t1) / 2;
            if (isSea(a[0] + (b[0] - a[0]) * tm, a[1] + (b[1] - a[1]) * tm)) {
                out.add(new double[][]{at(a, b, t0), at(a, b, t1)});
            }
        }
        return out;
    }

    /**
     * 선이 지나는 바다를 **물 구간 하나**(이름 「바다」, 종류 `sea`)로 — 호수·강과 같은 목록에 넣으면 패널의 「물 위 구간」 줄과 지도의 파란 토막이 그대로 쓴다.
     * {@code alreadyWater} 가 참인 토막(그 가운데 점이 호수·강 면 안)은 뺀다 — 강 하구에서 물 파일과 바다가 겹쳐도 한 번만 센다.
     * 바다 위가 아니거나 자료가 없으면 비어 있다.
     */
    public java.util.Optional<WaterBodies.Span> seaSpan(List<double[]> route, java.util.function.BiPredicate<Double, Double> alreadyWater) {
        double km = 0;
        List<double[][]> pieces = new ArrayList<>();
        for (int i = 1; i < route.size(); i++) {
            for (double[][] p : seaPieces(route.get(i - 1), route.get(i))) {
                if (alreadyWater != null && alreadyWater.test((p[0][0] + p[1][0]) / 2, (p[0][1] + p[1][1]) / 2)) {
                    continue;
                }
                km += Haversine.distanceKm(p[0][0], p[0][1], p[1][0], p[1][1]);
                pieces.add(p);
            }
        }
        return km > 0 ? java.util.Optional.of(new WaterBodies.Span(SEA_NAME, SEA, km, pieces)) : java.util.Optional.empty();
    }

    /** 선(꼭짓점 줄)이 바다 위를 지나는 길이(km) — 자료가 없으면 0 이다(`available()` 로 구별한다) */
    public double seaKm(List<double[]> route) {
        double km = 0;
        for (int i = 1; i < route.size(); i++) {
            for (double[][] p : seaPieces(route.get(i - 1), route.get(i))) {
                km += Haversine.distanceKm(p[0][0], p[0][1], p[1][0], p[1][1]);
            }
        }
        return km;
    }

    private static double[] at(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    /** 선분 p1→p2 위에서 q1→q2 와 만나는 자리(0~1). 안 만나면 null — 평면 근사다(짧은 선분) */
    private static Double crossingAt(double[] p1, double[] p2, double q1x, double q1y, double q2x, double q2y) {
        double rx = p2[0] - p1[0], ry = p2[1] - p1[1];
        double sx = q2x - q1x, sy = q2y - q1y;
        double den = rx * sy - ry * sx;
        if (den == 0) {
            return null;
        }
        double qpx = q1x - p1[0], qpy = q1y - p1[1];
        double t = (qpx * sy - qpy * sx) / den;
        double u = (qpx * ry - qpy * rx) / den;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? t : null;
    }
}
