package com.railfeas.calc;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 기존 도시철도의 **공표 표정속도**. `data/seed/rail_speed.csv` 를 읽는다 — 수단별
 * 표정속도 중앙값의 원본과 같은 파일이다.
 *
 * <p>쓰는 곳은 편익의 전환 전 시간이다. 2호선을 따라 지하철을 그리면 2호선을 타던 사람은
 * 시내버스가 아니라 2호선에서 갈아탄다 — 그 사람이 아끼는 시간은 2호선보다 빨라진 만큼이다.
 *
 * <p><b>노선 이름 표기가 파일마다 다르다.</b> 역 좌표는 `2호선`·`부산 도시철도 1호선`·
 * `김포골드라인`, 속도 파일은 `서울 2호선`·`부산 1호선`·`김포 골드라인` 이다. 둘 다
 * {@link #normalize} 로 맞춘다 — 조용히 안 맞으면 기준이 시내버스로 돌아가는데 화면에는
 * 아무 표시가 없다.
 *
 * <p><b>실측만 쓰고, 한 노선에 실측 값이 둘이면 고르지 않는다.</b> 대전 2호선은 미개통
 * 설계값이고, GTX-A 는 구간마다 다른 값이 적혀 있다. 고르면 그 구간의 값이 노선 전체의
 * 값이 된다. <b>모르는 노선은 null</b> 이다 — 9호선·코레일 광역선·대구·광주·대전은
 * 공표 표정속도를 아직 못 구했다(`docs/research-log.md`).
 */
@Component
public class UrbanLineSpeed {

    private static final Logger log = LoggerFactory.getLogger(UrbanLineSpeed.class);
    private static final String MEASURED = "실측";

    private final Path file;
    private final Map<String, Double> byLine = new HashMap<>();
    private final Set<String> ambiguous = new HashSet<>();

    public UrbanLineSpeed(@Value("${app.rail-speed-file}") String file) {
        this.file = Path.of(file);
    }

    /**
     * `서울 2호선` → `2호선`, `부산 도시철도 1호선` → `부산1호선`, `신분당선(연장)` → `신분당선`.
     * 괄호 꼬리는 같은 노선의 연장 구간이라 떼고, 지선(`서울 2호선 성수지선`)은 이름에 남아
     * 본선과 섞이지 않는다.
     */
    static String normalize(String name) {
        return name.replaceAll("\\(.*\\)", "")
                .replace("도시철도", "")
                .replace(" ", "")
                .replaceFirst("^서울(?=\\d)", "");
    }

    @PostConstruct
    void load() {
        if (!Files.exists(file)) {
            // 없으면 도시철도 기준만 빠진다 — 편익은 시내버스 기준으로 돈다
            log.warn("{} 없음 — 기존 도시철도의 소요시간을 비운다", file);
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<String> columns = List.of(reader.readLine().replace("﻿", "").split(","));
            int lineIdx = columns.indexOf("line");
            int speedIdx = columns.indexOf("speed_kmh");
            int basisIdx = columns.indexOf("basis");
            // 마지막 `source` 칸에 쉼표가 들어갈 수 있어 칸 수만큼만 자른다
            int limit = columns.size();
            String row;
            while ((row = reader.readLine()) != null) {
                String[] f = row.split(",", limit);
                if (f.length < limit || !MEASURED.equals(f[basisIdx].trim())
                        || f[speedIdx].isBlank()) {
                    continue;
                }
                add(f[lineIdx].trim(), Double.parseDouble(f[speedIdx].trim()));
            }
        } catch (Exception e) {
            throw new IllegalStateException(file + " 를 읽지 못했습니다", e);
        }
        log.info("기존 도시철도 표정속도 {}개 적재 (값이 둘이라 뺀 노선 {}개)",
                byLine.size(), ambiguous.size());
    }

    /** 실측 행 하나. 같은 노선에 다른 값이 오면 그 노선은 모르는 것으로 돌린다 */
    void add(String line, double speedKmh) {
        String key = normalize(line);
        if (ambiguous.contains(key)) {
            return;
        }
        Double before = byLine.putIfAbsent(key, speedKmh);
        if (before != null && before != speedKmh) {
            byLine.remove(key);
            ambiguous.add(key);
        }
    }

    /** 역 좌표 파일의 노선 이름으로 찾는다. 모르면 null */
    public Double of(String lineName) {
        return byLine.get(normalize(lineName));
    }
}
