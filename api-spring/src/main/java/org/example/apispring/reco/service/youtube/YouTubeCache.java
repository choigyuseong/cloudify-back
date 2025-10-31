package org.example.apispring.reco.service.youtube;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 🧠 YouTubeCache
 * - ConcurrentHashMap 기반 간단 TTL 캐시
 * - YouTubeService의 검색 결과(videoId) 임시 저장
 */
@Slf4j
@Component
public class YouTubeCache {

    private static final long TTL_MS = 1000L * 60 * 30; // 30분 TTL
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String value, long expiry) {}

    public void put(String key, String value) {
        cache.put(key, new CacheEntry(value, System.currentTimeMillis() + TTL_MS));
    }

    public String get(String key) {
        var entry = cache.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expiry()) {
            cache.remove(key);
            log.debug("🕒 Cache expired for {}", key);
            return null;
        }
        return entry.value();
    }

    public int size() {
        return cache.size();
    }
}
