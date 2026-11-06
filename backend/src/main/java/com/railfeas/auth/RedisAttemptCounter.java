package com.railfeas.auth;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisAttemptCounter implements AttemptCounter {

    private final StringRedisTemplate redis;

    public RedisAttemptCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long current(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    @Override
    public void increment(String key, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, window);
        }
    }

    @Override
    public void clear(String key) {
        redis.delete(key);
    }
}
