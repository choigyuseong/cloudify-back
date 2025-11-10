package org.example.apispring.global.security.jwt;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenJtiStore {

    private static final String KEY_PREFIX = "auth:rt:jti:";

    private final StringRedisTemplate redisTemplate;

    private String key(UUID userId) {
        return KEY_PREFIX + userId;
    }

    public void save(UUID userId, String jti, Duration ttl) {
        redisTemplate.opsForValue().set(key(userId), jti, ttl);
    }

    public boolean matches(UUID userId, String jti) {
        String current = redisTemplate.opsForValue().get(key(userId));
        return current != null && current.equals(jti);
    }

    public void clear(UUID userId) {
        redisTemplate.delete(key(userId));
    }
}