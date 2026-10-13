package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 도시 간 거리 노선에 "기존 KTX 활용 검토" 를 안내한다.
 *
 * {@link ExistingLineFinder} 는 출발·도착이 기존 역 1km 안이고 노선의 80% 가 역세권일 때만
 * 걸린다 — 역간격 1km 안팎인 도시철도의 판정이다. KTX 는 역이 수십 km 간격이라 그 판정으로는
 * 절대 안 걸린다. 인천~세종(120km)을 그리면 지하철 역 60개가 찍히는데, 정작 광명~오송을
 * 잇는 KTX 가 이미 있다는 말을 못 했다.
 *
 * 판정은 셋을 모두 만족해야 한다
 *   ① 노선이 도시철도 수단의 권장 연장(`max_length_km` 최댓값)보다 길다
 *   ② 같은 KTX 노선의 역 a 가 출발에서, b 가 도착에서 {@value #ACCESS_KM}km 안이다
 *   ③ a~b 거리가 출발~도착 직선거리의 {@value #MIN_SPAN_RATIO} 배 이상이다
 * ③이 없으면 양 끝이 같은 역 근처이거나 KTX 가 엉뚱한 방향으로 가도 걸린다.
 *
 * {@value #ACCESS_KM}km 는 판단값이다 — 인천 도심~광명역 16km, 세종청사~오송역 16km 가
 * 들어오는 거리. 역까지 따로 가야 하므로 거리를 문구에 함께 적는다.
 *
 * **단정하지 않는다.** 좌표는 OSM(공식 좌표로 검증), 노선-역 관계는 한국철도공사 공식
 * 자료지만 운행 횟수·소요시간은 보지 않는다. SRT 와 일반철도(무궁화·ITX)는 없다.
 */
@Component
public class IntercityRailFinder {

    private static final Logger log = LoggerFactory.getLogger(IntercityRailFinder.class);

    static final double ACCESS_KM = 20.0;
    static final double MIN_SPAN_RATIO = 0.5;

    private final Path stationFile;
    /** 노선 → 역 이름 → 좌표. 순서는 의미가 없다 (지선을 오가는 정차 목록이다) */
    private final Map<String, Map<String, double[]>> byLine = new LinkedHashMap<>();

    public IntercityRailFinder(@Value("${app.ktx-station-file}") String stationFile) {
        this.stationFile = Path.of(stationFile);
    }

    public record Match(String fromStation, double fromKm, String toStation, double toKm,
                        List<String> lines) {
        public String message() {
            return String.format("기존 %s 활용 검토 — %s역(출발에서 %.0fkm) ~ %s역(도착에서 %.0fkm)",
                    String.join("·", lines), fromStation, fromKm, toStation, toKm);
        }
    }

    @PostConstruct
    void load() {
        if (!Files.exists(stationFile)) {
            // 없으면 이 안내만 빠진다 — 기동을 막지 않는다 (ExistingLineFinder 와 같다)
            log.warn("{} 없음 — 기존 KTX 활용 안내가 비활성화된다", stationFile);
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(stationFile, StandardCharsets.UTF_8)) {
            List<String> columns = List.of(reader.readLine().replace("﻿", "").split(","));
            int lineIdx = columns.indexOf("line_name");
            int nameIdx = columns.indexOf("station_name");
            int latIdx = columns.indexOf("latitude");
            int lngIdx = columns.indexOf("longitude");
            String row;
            while ((row = reader.readLine()) != null) {
                String[] f = row.split(",");
                add(f[lineIdx], f[nameIdx],
                        Double.parseDouble(f[latIdx]), Double.parseDouble(f[lngIdx]));
            }
        } catch (Exception e) {
            throw new IllegalStateException(stationFile + " 를 읽지 못했습니다", e);
        }
        log.info("KTX 노선 {}개 적재", byLine.size());
    }

    void add(String line, String station, double lat, double lng) {
        byLine.computeIfAbsent(line, k -> new LinkedHashMap<>()).put(station, new double[]{lat, lng});
    }

    /**
     * @param route        그린 노선의 꼭짓점
     * @param lengthKm     그린 길이
     * @param urbanMaxKm   도시철도 수단의 권장 연장 최댓값. 이보다 짧으면 판정하지 않는다
     */
    public Match find(List<double[]> route, double lengthKm, double urbanMaxKm) {
        if (byLine.isEmpty() || route.size() < 2 || lengthKm <= urbanMaxKm) {
            return null;
        }
        double[] start = route.get(0);
        double[] end = route.get(route.size() - 1);
        double direct = km(start, end);

        Match best = null;
        for (Map<String, double[]> stations : byLine.values()) {
            String a = nearest(stations, start);
            String b = nearest(stations, end);
            if (a == null || b == null || a.equals(b)) {
                continue;
            }
            double fromKm = km(start, stations.get(a));
            double toKm = km(end, stations.get(b));
            if (fromKm > ACCESS_KM || toKm > ACCESS_KM
                    || km(stations.get(a), stations.get(b)) < direct * MIN_SPAN_RATIO) {
                continue;
            }
            if (best == null || fromKm + toKm < best.fromKm() + best.toKm()) {
                best = new Match(a, fromKm, b, toKm, List.of());
            }
        }
        if (best == null) {
            return null;
        }
        // 같은 두 역을 잇는 노선을 모두 적는다 (광명~오송은 경부·호남·전라·경전선이 다 선다)
        TreeSet<String> lines = new TreeSet<>();
        for (Map.Entry<String, Map<String, double[]>> e : byLine.entrySet()) {
            if (e.getValue().containsKey(best.fromStation())
                    && e.getValue().containsKey(best.toStation())) {
                lines.add(e.getKey());
            }
        }
        return new Match(best.fromStation(), best.fromKm(), best.toStation(), best.toKm(),
                compact(lines));
    }

    /** `KTX 경부선`, `KTX 호남선` → `KTX 경부선`, `호남선` — 문구가 150자 칸을 넘지 않게 */
    private static List<String> compact(TreeSet<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            out.add(out.isEmpty() ? line : line.replaceFirst("^KTX ", ""));
        }
        return out;
    }

    private static String nearest(Map<String, double[]> stations, double[] point) {
        String best = null;
        double bestKm = Double.MAX_VALUE;
        for (Map.Entry<String, double[]> e : stations.entrySet()) {
            double d = km(point, e.getValue());
            if (d < bestKm) {
                bestKm = d;
                best = e.getKey();
            }
        }
        return best;
    }

    private static double km(double[] a, double[] b) {
        return Haversine.distanceKm(a[0], a[1], b[0], b[1]);
    }
}
