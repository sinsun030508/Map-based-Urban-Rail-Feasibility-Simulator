package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 산 구간 계산은 1~3초라 Redis 에 담아 둔다. **캐시는 보조다** — 죽거나 값이 깨져도 계산은 된다.
 * 인구 집계는 메모리에서 1.5~5ms 라 담아도 빨라지지 않아 담지 않는다(노트 참고).
 */
class MountainPlansTest {

    private static final double LAT0 = 36.0, LNG0 = 128.0, KM_LAT = 111.195;
    private static final List<double[]> ROUTE = List.of(new double[]{LAT0, LNG0}, new double[]{LAT0 + 10 / KM_LAT, LNG0});

    /** 가운데 원뿔 산. 몇 번 읽혔는지 센다 — 캐시가 맞으면 지형을 읽지 않는다 */
    private final AtomicInteger reads = new AtomicInteger();
    private final Elevation terrain = (lat, lng) -> {
        reads.incrementAndGet();
        double r = Math.hypot((lat - LAT0) * KM_LAT - 5, (lng - LNG0) * 90);
        return OptionalDouble.of(Math.max(2, 400 * (1 - r / 2)));
    };

    private static class MemoryStore implements PlanStore {
        final Map<String, String> map = new HashMap<>();
        Duration ttl;

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(map.get(key));
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            map.put(key, value);
            this.ttl = ttl;
        }
    }

    private static class BrokenStore implements PlanStore {
        @Override
        public Optional<String> get(String key) {
            throw new IllegalStateException("Redis 연결 실패");
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            throw new IllegalStateException("Redis 연결 실패");
        }
    }

    private static MountainPlans plans(PlanStore store) {
        return new MountainPlans(store, new ObjectMapper());
    }

    @Test
    @DisplayName("같은 노선을 다시 물으면 지형을 읽지 않고 같은 답을 준다")
    void 두번째는_캐시() {
        MemoryStore store = new MemoryStore();
        var first = plans(store).plan(ROUTE, terrain, 0.59);
        int afterFirst = reads.get();
        var second = plans(store).plan(ROUTE, terrain, 0.59);

        assertThat(afterFirst).isPositive();
        assertThat(reads.get()).isEqualTo(afterFirst);
        assertThat(second.mountains()).hasSameSizeAs(first.mountains());
        assertThat(second.mountains().get(0).tunnelKm()).isEqualTo(first.mountains().get(0).tunnelKm());
        assertThat(second.mountains().get(0).costlier()).isEqualTo(first.mountains().get(0).costlier());
        assertThat(second.mountains().get(0).pieces().get(0).get(0)).containsExactly(first.mountains().get(0).pieces().get(0).get(0));
        assertThat(second.points()).hasSameSizeAs(first.points());
        assertThat(store.ttl).isPositive();
    }

    @Test
    @DisplayName("Redis 가 죽어도 계산은 된다")
    void 저장소가_죽어도_된다() {
        var plan = plans(new BrokenStore()).plan(ROUTE, terrain, 0.59);

        assertThat(plan.mountains()).hasSize(1);
    }

    @Test
    @DisplayName("담긴 값이 깨져 있으면 다시 계산한다")
    void 깨진_값() {
        MemoryStore store = new MemoryStore();
        store.map.put(MountainPlans.key(ROUTE, 0.59), "{깨진 값");

        assertThat(plans(store).plan(ROUTE, terrain, 0.59).mountains()).hasSize(1);
        assertThat(reads.get()).isPositive();
    }

    @Test
    @DisplayName("키는 노선과 손익분기로 갈린다 — 단가가 바뀌면 다른 답이다")
    void 키() {
        List<double[]> other = List.of(new double[]{LAT0, LNG0}, new double[]{LAT0 + 11 / KM_LAT, LNG0});

        assertThat(MountainPlans.key(ROUTE, 0.59)).isEqualTo(MountainPlans.key(ROUTE, 0.59));
        assertThat(MountainPlans.key(ROUTE, 0.59)).isNotEqualTo(MountainPlans.key(other, 0.59));
        assertThat(MountainPlans.key(ROUTE, 0.59)).isNotEqualTo(MountainPlans.key(ROUTE, 0.60));
        assertThat(MountainPlans.key(ROUTE, 0.59)).isNotEqualTo(MountainPlans.key(ROUTE, null));
        assertThat(MountainPlans.key(ROUTE, 0.59)).contains(":v" + MountainDetour.PLAN_VERSION + ":");
    }
}
