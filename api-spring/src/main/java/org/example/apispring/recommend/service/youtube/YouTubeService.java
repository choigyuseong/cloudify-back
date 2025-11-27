package org.example.apispring.recommend.service.youtube;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class YouTubeService {

    @Value("${cloudify.youtube.apiKey:}")
    private String apiKey;

    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";
    private final RestTemplate restTemplate = new RestTemplate();
    private final YouTubeCache cache;

    public YouTubeService(YouTubeCache cache) {
        this.cache = cache;
    }

    /**
     * 🎬 YouTube 검색 (비동기)
     * 제목 + 아티스트 기반으로 videoId를 탐색
     */
    @Async
    public CompletableFuture<String> fetchVideoIdAsync(String title, String artist) {
        return CompletableFuture.supplyAsync(() -> fetchVideoIdBySearch(title, artist));
    }

    /**
     * 🎬 YouTube 검색 (동기)
     * 캐시 확인 후, 없으면 API 호출
     */
    public String fetchVideoIdBySearch(String title, String artist) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("❌ YouTube API key is missing");
            return null;
        }

        if (title == null || artist == null) return null;

        String key = (title + "|" + artist).toLowerCase();

        // ✅ 캐시 조회
        String cached = cache.get(key);
        if (cached != null) {
            log.debug("⚡ Cache Hit [{} - {}] → {}", title, artist, cached);
            return cached;
        }

        try {
            String query = title + " " + artist + " official audio";
            String url = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                    .queryParam("part", "snippet")
                    .queryParam("q", query)
                    .queryParam("maxResults", 5)
                    .queryParam("type", "video")
                    .queryParam("key", apiKey)
                    .toUriString();

            String response = restTemplate.getForObject(url, String.class);
            JSONObject json = new JSONObject(response);
            JSONArray items = json.getJSONArray("items");

            double bestScore = 0.0;
            String bestId = null;

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String videoId = item.getJSONObject("id").getString("videoId");
                JSONObject snippet = item.getJSONObject("snippet");
                String videoTitle = snippet.getString("title").toLowerCase();

                double score = similarity(videoTitle, (title + " " + artist).toLowerCase());
                if (score > bestScore) {
                    bestScore = score;
                    bestId = videoId;
                }
            }

            if (bestId != null) {
                cache.put(key, bestId);
                log.info("🎬 [YouTube Fetch] {} - {} | score={}", title, artist, bestScore);
            } else {
                log.warn("⚠️ No match found for {} - {}", title, artist);
            }

            return bestId;

        } catch (Exception e) {
            log.error("❌ YouTube API error: {}", e.getMessage());
            return null;
        }
    }

    private double similarity(String a, String b) {
        String[] sa = a.split("\\s+");
        String[] sb = b.split("\\s+");
        int common = 0;
        for (String s1 : sa) for (String s2 : sb) if (s1.equals(s2)) common++;
        return (double) common / (sa.length + sb.length - common + 1e-6);
    }

    // ✅ 헬퍼 URL 생성기
    public static String watchUrl(String id) { return "https://www.youtube.com/watch?v=" + id; }
    public static String embedUrl(String id) { return "https://www.youtube.com/embed/" + id; }
    public static String thumbnailUrl(String id) { return "https://img.youtube.com/vi/" + id + "/hqdefault.jpg"; }
}
