package com.railfeas.calc;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 에 담는다. 연결은 처음 쓸 때 맺으므로 Redis 가 없어도 서버는 뜬다 */
@Component
public class RedisPlanStore implements PlanStore {

    private final StringRedisTemplate redis;

    public RedisPlanStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }
}
