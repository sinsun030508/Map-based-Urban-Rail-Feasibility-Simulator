package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 집계구 인구·동별 종사자를 메모리에 올려 노선 주변 인구를 센다.
 *
 * 왜 파일인가
 *   SGIS 를 요청마다 부르면 느리고 호출 제한도 있다. ETL 이 이미 받아 둔
 *   data/build/*.csv 를 그대로 읽는다. 전국이 아니라 **수집한 77개 시군구(수도권 위주)만**
 *   담겨 있어, 그 밖 지역은 인구가 0 으로 나온다 — 결과에 경고로 표시할 것.
 *   나중에 SGIS 실시간 호출 + Redis 캐싱으로 바꾼다.
 */
@Component
public class PopulationIndex {

    private static final Logger log = LoggerFactory.getLogger(PopulationIndex.class);
    private static final double CELL = 0.01;      // 약 1.1km — 버킷 한 변

    private final Path censusFile;
    private final Path workerFile;

    /** 집계구 한 칸. 이름은 행정동명으로, 정차역 이름을 만들 때 쓴다 (종사자 자료엔 없다) */
    public record Cell(double lat, double lng, double value, String name) {
    }

    private final Map<Long, List<Cell>> census = new HashMap<>();
    private final Map<Long, List<Cell>> workers = new HashMap<>();

    public PopulationIndex(@Value("${app.census-file}") String censusFile,
                           @Value("${app.worker-file}") String workerFile) {
        this.censusFile = Path.of(censusFile);
        this.workerFile = Path.of(workerFile);
    }

    @PostConstruct
    void load() {
        load(censusFile, census, "population");
        load(workerFile, workers, "workers");
        log.info("인구 격자 {}칸, 종사자 격자 {}칸 적재", census.size(), workers.size());
    }

    private void load(Path path, Map<Long, List<Cell>> into, String valueColumn) {
        if (!Files.exists(path)) {
            // 없으면 인구 0 으로 계산되고 결과에 경고가 붙는다 — 기동은 막지 않는다
            log.warn("{} 없음 — 수요 추정이 비활성화된다 (etl/sgis_population.py 실행 필요)", path);
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return;
            }
            String[] columns = header.replace("﻿", "").split(",");
            int latIdx = indexOf(columns, "latitude");
            int lngIdx = indexOf(columns, "longitude");
            int valIdx = indexOf(columns, valueColumn);
            int nameIdx = optionalIndexOf(columns, "dong_name");

            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(",");
                if (f.length <= Math.max(valIdx, Math.max(latIdx, lngIdx))) {
                    continue;
                }
                double lat = Double.parseDouble(f[latIdx]);
                double lng = Double.parseDouble(f[lngIdx]);
                double value = Double.parseDouble(f[valIdx]);
                String name = nameIdx >= 0 && f.length > nameIdx ? f[nameIdx].trim() : null;
                into.computeIfAbsent(cellKey(lat, lng), k -> new ArrayList<>())
                        .add(new Cell(lat, lng, value, name));
            }
        } catch (Exception e) {
            throw new IllegalStateException(path + " 를 읽지 못했습니다", e);
        }
    }

    /** 없을 수도 있는 컬럼 — 옛 CSV 에는 dong_name 이 없다 */
    private int optionalIndexOf(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].trim().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOf(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].trim().equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException(name + " 컬럼이 없습니다");
    }

    private long cellKey(double lat, double lng) {
        return (long) Math.floor(lat / CELL) * 100_000L + (long) Math.floor(lng / CELL);
    }

    public long populationNear(List<double[]> points, double radiusKm) {
        return sumNear(census, points, radiusKm);
    }

    public long workersNear(List<double[]> points, double radiusKm) {
        return sumNear(workers, points, radiusKm);
    }

    /**
     * 좌표 목록 중 하나라도 반경 안에 들면 더한다.
     * 같은 칸을 두 번 더하지 않도록 식별자로 걸러낸다 — 노선 위 점들이 겹치기 때문이다.
     */
    private long sumNear(Map<Long, List<Cell>> index, List<double[]> points, double radiusKm) {
        if (index.isEmpty()) {
            return 0;
        }
        Set<Cell> counted = new HashSet<>();
        double total = 0;
        for (double[] p : points) {
            for (Cell cell : near(index, p[0], p[1], radiusKm)) {
                if (counted.add(cell)) {
                    total += cell.value();
                }
            }
        }
        return Math.round(total);
    }

    /**
     * 사각 범위 안의 집계구를 [위도, 경도, 인구] 로 돌려준다 — 3D 인구밀도 표시용.
     * 전체가 5만 칸이라 그대로 내보내면 응답이 커진다. 화면 범위로 자르고 상한을 둔다.
     */
    public List<double[]> cellsWithin(double minLat, double minLng,
                                      double maxLat, double maxLng, int limit) {
        List<double[]> out = new ArrayList<>();
        for (List<Cell> bucket : census.values()) {
            for (Cell c : bucket) {
                if (c.lat() >= minLat && c.lat() <= maxLat
                        && c.lng() >= minLng && c.lng() <= maxLng) {
                    out.add(new double[]{c.lat(), c.lng(), c.value()});
                    if (out.size() >= limit) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /** 한 점 반경 안의 집계구. 정차역 배치가 이걸로 인구 피크를 찾는다 */
    public List<Cell> censusNear(double lat, double lng, double radiusKm) {
        return near(census, lat, lng, radiusKm);
    }

    private List<Cell> near(Map<Long, List<Cell>> index, double lat, double lng, double radiusKm) {
        if (index.isEmpty()) {
            return List.of();
        }
        int span = (int) Math.ceil(radiusKm / 100.0 / CELL) + 1;
        long baseLat = (long) Math.floor(lat / CELL);
        long baseLng = (long) Math.floor(lng / CELL);
        List<Cell> found = new ArrayList<>();
        for (long dLat = -span; dLat <= span; dLat++) {
            for (long dLng = -span; dLng <= span; dLng++) {
                List<Cell> bucket = index.get((baseLat + dLat) * 100_000L + baseLng + dLng);
                if (bucket == null) {
                    continue;
                }
                for (Cell cell : bucket) {
                    if (Haversine.distanceKm(lat, lng, cell.lat(), cell.lng()) <= radiusKm) {
                        found.add(cell);
                    }
                }
            }
        }
        return found;
    }
}
