package com.railfeas.transit;

import java.time.Duration;
import java.time.LocalDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 에서 날짜별로 센다. 키는 이틀 뒤 사라진다. */
@Component
public class RedisDailyQuota implements DailyQuota {

    private final StringRedisTemplate redis;

    public RedisDailyQuota(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(LocalDate day, int limit) {
        String key = "railfeas:transit:quota:" + day;
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            throw new IllegalStateException("호출 횟수를 세지 못했습니다");
        }
        if (count == 1) {
            redis.expire(key, Duration.ofDays(2));
        }
        return count <= limit;
    }
}
