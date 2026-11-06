package com.railfeas.calc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 산 구간 계산을 Redis 에 담아 둔다. 서울~부산이 1~3초라, 같은 노선을 다시 열거나 구조만 바꿀 때 기다리지 않게 한다.
 *
 * <p><b>제안서는 인구 집계를 담으라고 했지만 담지 않았다</b> — 메모리 색인이라 긴 노선도 1.5~5ms 로, Redis 왕복과 비슷하거나
 * 더 걸린다. 실제로 느린 곳에 쓴다.
 *
 * <p><b>캐시는 보조다.</b> Redis 가 죽거나 값이 깨져도 계산으로 넘어간다 — 호수·산 안내가 그랬듯 보조 기능이 본 기능을
 * 막지 않는다. 구조는 키에 없다 — 답이 구조와 무관해(지하는 컨트롤러가 먼저 거른다) 구조만 바꿔도 캐시가 맞는다.
 * 지형은 받은 뒤 바뀌지 않고, 판정 코드를 고치면 {@link MountainDetour#PLAN_VERSION} 을 올린다.
 */
@Component
public class MountainPlans {

    private static final Logger log = LoggerFactory.getLogger(MountainPlans.class);
    private static final Duration TTL = Duration.ofDays(7);

    private final PlanStore store;
    private final ObjectMapper json;

    public MountainPlans(PlanStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    public MountainDetour.Plan plan(List<double[]> route, Elevation terrain, Double breakEven) {
        String key = key(route, breakEven);
        Optional<MountainDetour.Plan> cached = read(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        MountainDetour.Plan plan = MountainDetour.plan(route, terrain, breakEven);
        write(key, plan);
        return plan;
    }

    private Optional<MountainDetour.Plan> read(String key) {
        try {
            Optional<String> value = store.get(key);
            return value.isEmpty() ? Optional.empty()
                    : Optional.of(json.readValue(value.get(), MountainDetour.Plan.class));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("산 구간 캐시를 못 읽어 다시 계산합니다 — {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void write(String key, MountainDetour.Plan plan) {
        try {
            store.put(key, json.writeValueAsString(plan), TTL);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("산 구간 캐시에 못 담았습니다 — {}", e.getMessage());
        }
    }

    /** 좌표는 요청과 같은 소수 7자리로 맞춘다. 손익분기가 바뀌면(단가 수정) 도는 구간이 달라져 다른 답이다 */
    static String key(List<double[]> route, Double breakEven) {
        StringBuilder text = new StringBuilder(breakEven == null ? "none" : String.format(Locale.ROOT, "%.6f", breakEven));
        for (double[] p : route) {
            text.append(String.format(Locale.ROOT, "|%.7f,%.7f", p[0], p[1]));
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return "railfeas:mountain:v" + MountainDetour.PLAN_VERSION + ":" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
