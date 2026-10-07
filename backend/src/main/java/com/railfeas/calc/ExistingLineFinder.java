package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 그린 노선이 기존 노선과 겹치는지 본다.
 *
 * 예비타당성조사도 신설안만 보지 않고 **기존 시설 활용안과 비교한다.** 이미 전철이 다니는
 * 길에 또 짓는 안이 비교표 1위로 올라오면 그 결과는 쓸모가 없다. 겹치면 먼저 알려 준다.
 *
 * 판정은 두 가지를 모두 만족해야 한다
 *   ① 출발·도착이 그 노선의 역에서 {@value #TERMINAL_KM}km 안에 있다
 *   ② 노선을 따라 훑은 점 중 {@value #MIN_COVERAGE} 이상이 그 노선 역 반경 안에 든다
 * 양 끝만 보면 전혀 다른 경로로 돌아가는 노선까지 걸리고, 겹침 비율만 보면
 * 일부 구간만 스치는 노선이 걸린다.
 *
 * **단정하지 않는다.** 선형이 아니라 역 위치로만 재고, 지방 좌표는 OSM(집단 편집 자료)
 * 에서 받았기 때문에 "활용을 검토하라"는 안내까지만 한다. 신설 노선은 아직 없다.
 */
@Component
public class ExistingLineFinder {

    private static final Logger log = LoggerFactory.getLogger(ExistingLineFinder.class);

    private static final double TERMINAL_KM = 1.0;    // 종점이 기존 역에 붙었다고 볼 거리
    private static final double CORRIDOR_KM = 1.0;    // 노선을 따라갈 때 역세권으로 볼 거리
    private static final double STEP_KM = 0.5;        // 노선을 훑는 간격
    // 이만큼 겹치면 같은 축으로 본다. 0.6 으로 뒀더니 **양 끝 역 무리만으로** 4km 노선의
    // 67% 가 덮여(반경 1km × 양쪽) 가운데가 텅 빈 우회 노선까지 걸렸다. 실제로 겹치는
    // 노선은 0.9 를 넘으므로 0.8 이면 둘을 가른다 (테스트로 고정)
    private static final double MIN_COVERAGE = 0.8;

    /**
     * 좌표 출처가 둘이다. 수도권은 서울 열린데이터광장(`seoul_ridership.py`),
     * 지방은 OpenStreetMap(`osm_regional_stations.py`). **섞어 두지 않고 따로 받는다** —
     * 신뢰도가 다르고 각자 다시 만들어지는 주기도 다르다. 둘 다 없어도 기동은 막지 않는다.
     */
    private final List<Path> stationFiles;
    private final Map<String, List<double[]>> byLine = new HashMap<>();

    public ExistingLineFinder(@Value("${app.station-file}") String stationFile,
                              @Value("${app.regional-station-file}") String regionalFile) {
        this.stationFiles = List.of(Path.of(stationFile), Path.of(regionalFile));
    }

    /** 겹치는 기존 노선. coverage 는 0~1 */
    public record Match(String lineName, double coverage) {
        public String message() {
            return String.format("기존 %s 활용 검토 — 노선의 %.0f%% 가 기존 역 1km 안이다",
                    lineName, coverage * 100);
        }
    }

    @PostConstruct
    void load() {
        for (Path file : stationFiles) {
            if (Files.exists(file)) {
                loadFile(file);
            } else {
                // 없으면 그 범위만 안내를 못 할 뿐 계산은 그대로 돈다 — 기동을 막지 않는다
                log.warn("{} 없음 — 이 범위의 기존 노선 활용 안내가 비활성화된다", file);
            }
        }
        log.info("기존 노선 {}개 적재 (역 {}개)", byLine.size(),
                byLine.values().stream().mapToInt(List::size).sum());
    }

    private void loadFile(Path stationFile) {
        try (BufferedReader reader = Files.newBufferedReader(stationFile, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return;
            }
            String[] columns = header.replace("﻿", "").split(",");
            int lineIdx = indexOf(columns, "line_name");
            int latIdx = indexOf(columns, "latitude");
            int lngIdx = indexOf(columns, "longitude");

            String row;
            while ((row = reader.readLine()) != null) {
                String[] f = row.split(",");
                if (f.length <= Math.max(lineIdx, Math.max(latIdx, lngIdx))) {
                    continue;
                }
                try {
                    byLine.computeIfAbsent(f[lineIdx].trim(), k -> new ArrayList<>())
                            .add(new double[]{Double.parseDouble(f[latIdx]),
                                    Double.parseDouble(f[lngIdx])});
                } catch (NumberFormatException ignored) {
                    // 좌표를 못 붙인 역이 있다 — 그 역만 건너뛴다
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(stationFile + " 를 읽지 못했습니다", e);
        }
    }

    /** 적재된 노선 수. 두 출처가 합쳐졌는지 테스트에서 본다 */
    int lineCount() {
        return byLine.size();
    }

    private int indexOf(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].trim().equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException(name + " 컬럼이 없습니다");
    }

    /** 가장 많이 겹치는 기존 노선. 없으면 null */
    public Match find(List<double[]> route) {
        if (byLine.isEmpty() || route.size() < 2) {
            return null;
        }
        double[] start = route.get(0);
        double[] end = route.get(route.size() - 1);
        List<double[]> samples = sample(route);

        Match best = null;
        for (Map.Entry<String, List<double[]>> entry : byLine.entrySet()) {
            List<double[]> stations = entry.getValue();
            // 양 끝이 그 노선에 붙어 있지 않으면 같은 축이라고 보기 어렵다
            if (!within(start, stations, TERMINAL_KM) || !within(end, stations, TERMINAL_KM)) {
                continue;
            }
            long covered = samples.stream()
                    .filter(p -> within(p, stations, CORRIDOR_KM))
                    .count();
            double coverage = (double) covered / samples.size();
            if (coverage >= MIN_COVERAGE && (best == null || coverage > best.coverage())) {
                best = new Match(entry.getKey(), coverage);
            }
        }
        return best;
    }

    private boolean within(double[] point, List<double[]> stations, double radiusKm) {
        for (double[] s : stations) {
            if (Haversine.distanceKm(point[0], point[1], s[0], s[1]) <= radiusKm) {
                return true;
            }
        }
        return false;
    }

    /** 노선을 일정 간격으로 훑는다 — 꼭짓점만 보면 긴 구간이 통째로 빠진다 */
    private List<double[]> sample(List<double[]> route) {
        List<double[]> out = new ArrayList<>();
        out.add(route.get(0));
        for (int i = 1; i < route.size(); i++) {
            double[] a = route.get(i - 1);
            double[] b = route.get(i);
            double segment = Haversine.distanceKm(a[0], a[1], b[0], b[1]);
            int steps = Math.max(1, (int) Math.round(segment / STEP_KM));
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
            }
        }
        return out;
    }
}
