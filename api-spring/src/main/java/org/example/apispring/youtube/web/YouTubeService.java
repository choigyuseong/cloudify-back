package org.example.apispring.youtube.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.*;

@Service
public class YouTubeService {

    @Value("${cloudify.youtube.apiKey}")
    private String apiKey;

    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";
    private final RestTemplate restTemplate = new RestTemplate();

    // ✅ 캐시 (ConcurrentHashMap)
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    // 비동기 검색
    @Async
    public CompletableFuture<String> fetchVideoIdAsync(String title, String artist) {
        return CompletableFuture.supplyAsync(() -> fetchVideoIdBySearch(title, artist));
    }

    // ✅ 캐시 + 비동기 + 유사도 기반 검색
    public String fetchVideoIdBySearch(String title, String artist) {
        if (apiKey == null || apiKey.isBlank()) return null;
        if (title == null || artist == null) return null;

        String key = (title + "|" + artist).toLowerCase();
        if (cache.containsKey(key)) {
            System.out.printf("⚡ [Cache Hit] %s - %s → %s%n", title, artist, cache.get(key));
            return cache.get(key);
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

            double bestScore = 0;
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
                System.out.printf("🎬 [YouTube Fetch] %s - %s | score=%.2f → %s%n",
                        title, artist, bestScore, bestId);
            } else {
                System.out.printf("⚠️ [No Match] %s - %s%n", title, artist);
            }

            return bestId;

        } catch (Exception e) {
            System.err.println("❌ YouTube API error: " + e.getMessage());
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

    // ✅ 헬퍼 메서드
    public static String watchUrl(String videoId) { return "https://www.youtube.com/watch?v=" + videoId; }
    public static String embedUrl(String videoId) { return "https://www.youtube.com/embed/" + videoId; }
    public static String thumbnailUrl(String videoId) { return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg"; }
}
