package com.railfeas.transit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railfeas.calc.PlanStore;
import com.railfeas.scenario.ScenarioDto;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 두 점 사이의 실제 대중교통 경로를 TMAP 에 묻는다. B/C 와 무관한 보조 기능이라 실패해도 아무것도 막지 않는다.
 *
 * <p><b>한도를 지키는 것이 먼저다</b> — 무료 하루 10건이고 종량제로 바꾸면 돈이 든다. 같은 요청(좌표 소수 5자리 + 기준 시각)은
 * 12시간 담아 다시 주고(약관의 24시간 저장 제한 안), 부르기 전에 하루 상한을 센다. <b>Redis 가 죽으면 부르지 않는다</b>
 * (로그인 제한과 반대다). 예외 메시지는 로그에 옮기지 않는다 — 키가 섞일 수 있다.
 */
@Service
public class TransitService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration TTL = Duration.ofHours(12);
    private static final Logger log = LoggerFactory.getLogger(TransitService.class);

    private final TmapClient client;
    private final DailyQuota quota;
    private final PlanStore cache;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final int dailyLimit;
    private final Clock clock;

    @Autowired
    public TransitService(TmapClient client, DailyQuota quota, PlanStore cache, ObjectMapper mapper,
                          @Value("${app.tmap.app-key:}") String appKey,
                          @Value("${app.tmap.daily-limit:10}") int dailyLimit) {
        this(client, quota, cache, mapper, appKey, dailyLimit, Clock.system(KST));
    }

    TransitService(TmapClient client, DailyQuota quota, PlanStore cache, ObjectMapper mapper, String appKey,
                   int dailyLimit, Clock clock) {
        this.client = client;
        this.quota = quota;
        this.cache = cache;
        this.mapper = mapper;
        this.enabled = !appKey.isBlank();
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    public boolean enabled() {
        return enabled;
    }

    public TransitDto.Response search(ScenarioDto.PointRequest from, ScenarioDto.PointRequest to) {
        if (!enabled) {
            return TransitDto.Response.empty(TransitDto.Status.DISABLED);
        }
        BigDecimal fromLat = round(from.latitude());
        BigDecimal fromLng = round(from.longitude());
        BigDecimal toLat = round(to.latitude());
        BigDecimal toLng = round(to.longitude());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), KST);
        LocalDateTime at = SearchTime.next(now);
        // v2 — 선 좌표가 들기 전에 담은 답은 쓰지 않는다
        String key = "railfeas:transit:v2:" + fromLat + "," + fromLng + ":" + toLat + "," + toLng + ":" + at;

        Optional<TransitDto.Response> saved = read(key);
        if (saved.isPresent()) {
            return saved.get();
        }
        try {
            LocalDate today = now.toLocalDate();
            if (!quota.tryAcquire(today, dailyLimit)) {
                return TransitDto.Response.empty(TransitDto.Status.LIMIT);
            }
        } catch (RuntimeException e) {
            log.warn("대중교통 호출 횟수를 못 세어 부르지 않습니다 — {}", e.getClass().getSimpleName());
            return TransitDto.Response.empty(TransitDto.Status.FAILED);
        }
        TransitDto.Response result = ask(fromLat, fromLng, toLat, toLng, at);
        if (result.status() == TransitDto.Status.OK || result.status() == TransitDto.Status.NO_ROUTE) {
            write(key, result);
        }
        return result;
    }

    private TransitDto.Response ask(BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng,
                                    LocalDateTime at) {
        try {
            TmapClient.Reply reply = client.search(fromLat, fromLng, toLat, toLng, at);
            if (reply.status() == 429) {
                return TransitDto.Response.empty(TransitDto.Status.LIMIT);
            }
            if (reply.status() / 100 != 2) {
                log.warn("TMAP 대중교통 응답 {}", reply.status());
                return TransitDto.Response.empty(TransitDto.Status.FAILED);
            }
            JsonNode root = mapper.readTree(reply.body());
            List<TransitDto.Itinerary> itineraries = TmapParser.parse(root);
            if (!itineraries.isEmpty()) {
                return new TransitDto.Response(TransitDto.Status.OK, at.toString(), itineraries);
            }
            if (root.path("result").hasNonNull("status")) {
                return new TransitDto.Response(TransitDto.Status.NO_ROUTE, at.toString(), List.of());
            }
            log.warn("TMAP 대중교통 응답에서 경로를 못 읽었습니다");
            return TransitDto.Response.empty(TransitDto.Status.FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TransitDto.Response.empty(TransitDto.Status.FAILED);
        } catch (IOException | RuntimeException e) {
            log.warn("TMAP 대중교통 호출 실패 — {}", e.getClass().getSimpleName());
            return TransitDto.Response.empty(TransitDto.Status.FAILED);
        }
    }

    private Optional<TransitDto.Response> read(String key) {
        try {
            Optional<String> json = cache.get(key);
            if (json.isPresent()) {
                return Optional.of(mapper.readValue(json.get(), TransitDto.Response.class));
            }
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("담아 둔 대중교통 경로를 못 읽었습니다 — {}", e.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    private void write(String key, TransitDto.Response result) {
        try {
            cache.put(key, mapper.writeValueAsString(result), TTL);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("대중교통 경로를 담지 못했습니다 — {}", e.getClass().getSimpleName());
        }
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(5, RoundingMode.HALF_UP);
    }
}
