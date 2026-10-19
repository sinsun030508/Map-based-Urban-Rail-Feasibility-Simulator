package com.railfeas.calc;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 두 KTX 역 사이의 실제 소요시간. `etl/korail_travel_time.py` 가 TAGO 「열차정보」에서
 * 받아 `data/build/ktx_travel_time.csv` 에 적어 둔 것을 읽기만 한다.
 *
 * <p><b>요청할 때 API 를 부르지 않는다.</b> 비교표가 뜰 때마다 외부 API 를 부르면
 * API 가 느리거나 죽은 날 비교 화면 전체가 같이 멈춘다. 다른 기준값과 같은 방식으로
 * 미리 받아 파일에 둔다.
 *
 * <p><b>한 값이 아니라 계통별로 들고 있다.</b> 2026-10-20 서울~부산은 고속선 44편
 * 143~176분(중앙 164)과 더 싸고 느린 우회 11편 180~205분(중앙 198), 두 덩어리였다.
 * 화면의 좁은 칸에는 {@link #fastestMedian}(빠른 계통의 중앙값)만 쓰고, 갈리는 폭은
 * 설명 줄에서 {@link #legs} 로 펼친다. ETL 은 **양쪽에 3편 이상 있고 시간 범위가
 * 겹치지 않을 때만** 갈라 적는다 — 1편짜리 계통을 만들면 그 한 열차의 시각이 그대로
 * 대표값이 되어, 계통을 가른 목적이 뒤집힌다.
 *
 * <p><b>모르는 구간은 0 이 아니라 null 이다.</b> 0 으로 돌려주면 비교표에 "0분" 이 찍혀
 * KTX 가 순간이동하는 것처럼 보인다 — 빈 칸과 그 이유가 낫다.
 */
@Component
public class KtxTravelTime {

    private static final Logger log = LoggerFactory.getLogger(KtxTravelTime.class);

    /**
     * 한 계통. {@code rank} 1 이 가장 빠른 계통이다(운임 순서가 아니다 — ETL 이
     * 운임으로 묶되 중앙값으로 줄을 세운다).
     */
    public record Leg(int rank, int trains, int medianMin, int minMin, int maxMin) {
    }

    private final Path file;
    /** 역 두 곳을 정렬해 만든 열쇠 → 계통들. 대표역을 어느 쪽에서 골랐든 같은 구간이다 */
    private final Map<String, List<Leg>> byPair = new LinkedHashMap<>();

    public KtxTravelTime(@Value("${app.ktx-travel-time-file}") String file) {
        this.file = Path.of(file);
    }

    private static String key(String a, String b) {
        return a.compareTo(b) <= 0 ? a + '\u0000' + b : b + '\u0000' + a;
    }

    @PostConstruct
    void load() {
        if (!Files.exists(file)) {
            // 없으면 소요시간 칸만 빈다 — 기동을 막지 않는다 (ExistingLineFinder 와 같다)
            log.warn("{} 없음 — 기존 KTX 소요시간이 빈 칸으로 나간다", file);
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<String> columns = List.of(reader.readLine().replace("﻿", "").split(","));
            int from = columns.indexOf("from_station");
            int to = columns.indexOf("to_station");
            int rank = columns.indexOf("group_rank");
            int trains = columns.indexOf("trains");
            int median = columns.indexOf("median_min");
            int min = columns.indexOf("min_min");
            int max = columns.indexOf("max_min");
            if (median < 0 || rank < 0) {
                throw new IllegalStateException(
                        file + " 에 group_rank·median_min 열이 없습니다 — "
                                + "etl/korail_travel_time.py 를 다시 돌리세요");
            }
            String row;
            while ((row = reader.readLine()) != null) {
                String[] f = row.split(",");
                add(f[from], f[to], Integer.parseInt(f[rank]), Integer.parseInt(f[trains]),
                        Integer.parseInt(f[median]), Integer.parseInt(f[min]), Integer.parseInt(f[max]));
            }
        } catch (Exception e) {
            throw new IllegalStateException(file + " 를 읽지 못했습니다", e);
        }
        log.info("KTX 소요시간 {}구간 적재", byPair.size());
    }

    void add(String from, String to, int rank, int trains, int medianMin, int minMin, int maxMin) {
        List<Leg> legs = byPair.computeIfAbsent(key(from, to), k -> new ArrayList<>());
        legs.add(new Leg(rank, trains, medianMin, minMin, maxMin));
        legs.sort(Comparator.comparingInt(Leg::rank));
    }

    /** 그 구간의 계통들. 모르면 빈 목록 */
    public List<Leg> legs(String from, String to) {
        return byPair.getOrDefault(key(from, to), List.of());
    }

    /** 빠른 계통의 중앙값(분). <b>모르면 null</b> */
    public Integer fastestMedian(String from, String to) {
        List<Leg> legs = legs(from, to);
        return legs.isEmpty() ? null : legs.get(0).medianMin();
    }
}
