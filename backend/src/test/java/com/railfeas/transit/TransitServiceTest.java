package com.railfeas.transit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.railfeas.calc.PlanStore;
import com.railfeas.scenario.ScenarioDto;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 실제 API 를 부르지 않는다 — 가짜 호출·상한·저장소로 한도와 캐시 규칙을 본다. */
@ExtendWith(OutputCaptureExtension.class)
class TransitServiceTest {

    private static final String KEY = "test-app-key-123456";
    private static final String OK_BODY = "{\"metaData\":{\"plan\":{\"itineraries\":[{\"totalTime\":600,"
            + "\"legs\":[{\"mode\":\"WALK\",\"sectionTime\":600}]}]}}}";

    // 2026-11-28(토) 23:00 KST
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-28T14:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final FakeClient client = new FakeClient();
    private final FakeQuota quota = new FakeQuota();
    private final FakeStore store = new FakeStore();

    private TransitService service(String key) {
        return new TransitService(client, quota, store, new ObjectMapper(), key, 10, clock);
    }

    private static ScenarioDto.PointRequest point(String lat, String lng) {
        return new ScenarioDto.PointRequest(new BigDecimal(lat), new BigDecimal(lng));
    }

    private TransitDto.Response search() {
        return service(KEY).search(point("37.5665", "126.9780"), point("37.5133", "127.1001"));
    }

    @Test
    void 키가_없으면_꺼져_있고_부르지도_세지도_않는다() {
        TransitDto.Response r = service("  ").search(point("37.5", "127.0"), point("37.6", "127.1"));

        assertThat(r.status()).isEqualTo(TransitDto.Status.DISABLED);
        assertThat(service("").enabled()).isFalse();
        assertThat(service(KEY).enabled()).isTrue();
        assertThat(client.calls).isZero();
        assertThat(quota.days).isEmpty();
    }

    @Test
    void 경로를_돌려주고_다음_평일_여덟시_기준이며_한국_날짜로_센다() {
        TransitDto.Response r = search();

        assertThat(r.status()).isEqualTo(TransitDto.Status.OK);
        assertThat(r.searchedFor()).isEqualTo("2026-11-30T08:00");
        assertThat(r.itineraries()).hasSize(1);
        assertThat(client.at).isEqualTo(LocalDateTime.of(2026, 11, 30, 8, 0));
        assertThat(quota.days).containsExactly(LocalDate.of(2026, 11, 28));
        assertThat(quota.limit).isEqualTo(10);
        assertThat(store.ttl).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void 같은_요청은_캐시에서_주고_부르지도_세지도_않는다_여섯째_자리_차이는_같은_요청이다() {
        search();

        TransitDto.Response again = service(KEY)
                .search(point("37.566501", "126.978001"), point("37.513302", "127.100098"));

        assertThat(again.status()).isEqualTo(TransitDto.Status.OK);
        assertThat(again.itineraries()).hasSize(1);
        assertThat(client.calls).isEqualTo(1);
        assertThat(quota.days).hasSize(1);
    }

    @Test
    void 하루_상한을_넘으면_부르지_않고_담지도_않는다() {
        quota.allow = false;

        assertThat(search().status()).isEqualTo(TransitDto.Status.LIMIT);
        assertThat(client.calls).isZero();
        assertThat(store.values).isEmpty();
    }

    @Test
    void Redis_가_죽으면_부르지_않는다() {
        store.broken = true;
        quota.broken = true;

        assertThat(search().status()).isEqualTo(TransitDto.Status.FAILED);
        assertThat(client.calls).isZero();
    }

    @Test
    void TMAP_이_429_를_주면_한도_초과다() {
        client.reply = new TmapClient.Reply(429, "{}");

        assertThat(search().status()).isEqualTo(TransitDto.Status.LIMIT);
        assertThat(store.values).isEmpty();
    }

    @Test
    void 경로_없음_응답은_NO_ROUTE_로_담는다() {
        client.reply = new TmapClient.Reply(200, "{\"result\":{\"status\":11,\"message\":\"가깝다\"}}");

        assertThat(search().status()).isEqualTo(TransitDto.Status.NO_ROUTE);
        assertThat(search().status()).isEqualTo(TransitDto.Status.NO_ROUTE);
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void 모르는_모양_오류_상태_JSON_아님은_실패이고_담지_않는다() {
        for (TmapClient.Reply reply : List.of(new TmapClient.Reply(200, "{\"error\":{}}"),
                new TmapClient.Reply(403, OK_BODY), new TmapClient.Reply(200, "<html>"))) {
            client.reply = reply;
            assertThat(search().status()).as(reply.toString()).isEqualTo(TransitDto.Status.FAILED);
        }
        assertThat(store.values).isEmpty();
        assertThat(client.calls).isEqualTo(3);
    }

    @Test
    void 호출이_실패해도_키가_응답과_로그에_남지_않는다(CapturedOutput output) {
        client.failure = new IOException("connect failed appKey=" + KEY);

        TransitDto.Response r = search();

        assertThat(r.status()).isEqualTo(TransitDto.Status.FAILED);
        assertThat(r.toString()).doesNotContain(KEY);
        assertThat(output.getAll()).contains("IOException").doesNotContain(KEY);
    }

    private static class FakeClient implements TmapClient {
        int calls;
        LocalDateTime at;
        Reply reply = new Reply(200, OK_BODY);
        IOException failure;

        @Override
        public Reply search(BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng,
                            LocalDateTime when) throws IOException {
            calls++;
            at = when;
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }

    private static class FakeQuota implements DailyQuota {
        final List<LocalDate> days = new ArrayList<>();
        int limit;
        boolean allow = true;
        boolean broken;

        @Override
        public boolean tryAcquire(LocalDate day, int max) {
            if (broken) {
                throw new IllegalStateException("redis down");
            }
            days.add(day);
            limit = max;
            return allow;
        }
    }

    private static class FakeStore implements PlanStore {
        final Map<String, String> values = new HashMap<>();
        Duration ttl;
        boolean broken;

        @Override
        public Optional<String> get(String key) {
            if (broken) {
                throw new IllegalStateException("redis down");
            }
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void put(String key, String value, Duration duration) {
            values.put(key, value);
            ttl = duration;
        }
    }
}
