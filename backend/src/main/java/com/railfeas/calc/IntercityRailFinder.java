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
    /**
     * 고속선 역을 고르는 여유. 가장 가까운 역보다 이만큼 더 멀어도 고속선 역을 고른다.
     *
     * **"무조건 고속선" 이면 안 된다.** 서울역은 선로 계통이 `경부선`(재래선)이고 광명역이
     * `경부고속` 이라, 무조건 고속선을 고르면 서울시청에서 **1.5km 떨어진 서울역을 두고
     * 17km 떨어진 광명역**을 안내한다 — 지금보다 나쁘다. 그래서 "가까움" 을 이기지 않는
     * **동점 처리**로만 쓴다.
     *
     * 2km 는 판단값이다. 막으려는 것은 **좌표 잡음이 답을 정하는 것**이다 — 부산시청에서
     * 구포 7.67km / 부산 7.73km 로 **60m** 차이고, 좌표가 OSM 에서 오므로 편집 한 번에
     * 뒤집힌다. 어차피 역까지 따로 가야 하는 거리(최대 20km)에 견주면 2km 차이는 작고,
     * 고속선이냐 아니냐는 운행 편수와 속도를 크게 가른다.
     */
    static final double HIGH_SPEED_SLACK_KM = 2.0;

    private final Path stationFile;
    /** 노선 → 역 이름 → 역. 순서는 의미가 없다 (지선을 오가는 정차 목록이다) */
    private final Map<String, Map<String, Stop>> byLine = new LinkedHashMap<>();

    /**
     * @param highSpeed 선로 계통이 고속선인가 (`official_line` 에 '고속' 이 들었는가).
     *                  경부고속·호남고속 둘뿐이다 — 경강선(강릉선)은 250km/h 급이지만
     *                  공식 자료가 `경강선` 으로 적어 두어 여기서는 재래선으로 센다
     */
    record Stop(double lat, double lng, boolean highSpeed) {
    }

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
            int classIdx = columns.indexOf("official_line");
            if (classIdx < 0) {
                // 조용히 false 로 두면 고속선 우선이 **통째로 꺼진 채** 돌아간다
                throw new IllegalStateException(
                        stationFile + " 에 official_line 열이 없습니다 — "
                                + "etl/korail_ktx_stations.py 를 다시 돌리세요");
            }
            String row;
            while ((row = reader.readLine()) != null) {
                String[] f = row.split(",");
                add(f[lineIdx], f[nameIdx],
                        Double.parseDouble(f[latIdx]), Double.parseDouble(f[lngIdx]),
                        f[classIdx].contains("고속"));
            }
        } catch (Exception e) {
            throw new IllegalStateException(stationFile + " 를 읽지 못했습니다", e);
        }
        log.info("KTX 노선 {}개 적재", byLine.size());
    }

    void add(String line, String station, double lat, double lng, boolean highSpeed) {
        byLine.computeIfAbsent(line, k -> new LinkedHashMap<>())
                .put(station, new Stop(lat, lng, highSpeed));
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
        for (Map<String, Stop> stations : byLine.values()) {
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
        for (Map.Entry<String, Map<String, Stop>> e : byLine.entrySet()) {
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

    /**
     * 그 노선에서 이 지점에 쓸 역. 기본은 **가장 가까운 역**이고, 가장 가까운 역보다
     * {@value #HIGH_SPEED_SLACK_KM}km 안에 **고속선 역**이 있으면 그쪽을 고른다.
     *
     * 부산시청에서 구포 7.67km(경부선) / 부산 7.73km(경부고속)인데, 60m 차이로 구포가
     * 뽑히고 있었다. 좌표가 OSM 에서 오므로 편집 한 번에 답이 뒤집히는 자리다.
     * 여유를 넘어서까지 고속선을 고르지는 않는다 — 그러면 서울시청에서 서울역(1.5km)을
     * 두고 광명역(17km)을 안내한다.
     */
    private static String nearest(Map<String, Stop> stations, double[] point) {
        String best = null;
        double bestKm = Double.MAX_VALUE;
        for (Map.Entry<String, Stop> e : stations.entrySet()) {
            double d = km(point, e.getValue());
            if (d < bestKm) {
                bestKm = d;
                best = e.getKey();
            }
        }
        if (best == null) {
            return null;
        }
        String fast = null;
        double fastKm = Double.MAX_VALUE;
        for (Map.Entry<String, Stop> e : stations.entrySet()) {
            double d = km(point, e.getValue());
            if (e.getValue().highSpeed() && d <= bestKm + HIGH_SPEED_SLACK_KM && d < fastKm) {
                fastKm = d;
                fast = e.getKey();
            }
        }
        return fast != null ? fast : best;
    }

    private static double km(double[] a, Stop b) {
        return Haversine.distanceKm(a[0], a[1], b.lat(), b.lng());
    }

    private static double km(Stop a, Stop b) {
        return Haversine.distanceKm(a.lat(), a.lng(), b.lat(), b.lng());
    }

    private static double km(double[] a, double[] b) {
        return Haversine.distanceKm(a[0], a[1], b[0], b[1]);
    }
}
