package org.example.apispring.reco.service.youtube;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class YouTubeService {

    @Value("${cloudify.youtube.apiKey:}")
    private String apiKey;

    @Value("${cloudify.youtube.candidatesPerSearch:8}")
    private int candidatesPerSearch;

    @Value("${cloudify.youtube.earlyStopScore:0.90}")
    private double earlyStopScore;

    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";

    private final RestTemplate rest = new RestTemplate();
    private final YouTubeCache cache;

    public YouTubeService(YouTubeCache cache) {
        this.cache = cache;
    }

    // ---------------------------------------------------------
    // UTF-8 강제 인코딩 설정
    // ---------------------------------------------------------
    @PostConstruct
    public void init() {
        rest.setRequestFactory(new SimpleClientHttpRequestFactory());
        rest.getMessageConverters().stream()
                .filter(c -> c instanceof StringHttpMessageConverter)
                .forEach(c -> ((StringHttpMessageConverter) c)
                        .setDefaultCharset(StandardCharsets.UTF_8));

        log.info("🔥 YouTubeService UTF-8 initialized");
    }

    // ---------------------------------------------------------
    // 비동기 지원
    // ---------------------------------------------------------
    public CompletableFuture<String> fetchVideoIdAsync(String title, String artist) {
        return CompletableFuture.supplyAsync(() -> fetchVideoIdBySearch(title, artist));
    }

    // ---------------------------------------------------------
    // 핵심 검색(title + artist)
    // ---------------------------------------------------------
    public String fetchVideoIdBySearch(String title, String artist) {
        if (title == null || artist == null) {
            log.warn("❌ Title or artist is null");
            return null;
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.error("❌ Missing YouTube API KEY");
            return null;
        }

        final String cacheKey = (title + "|" + artist).toLowerCase(Locale.ROOT);

        // 캐시 확인
        String cached = cache.get(cacheKey);
        if (cached != null) {
            log.info("⚡ YouTube cache hit: {} -> {}", cacheKey, cached);
            return cached;
        }

        try {
            // ---------------------------------------------------------
            // 검색어 조합
            // ---------------------------------------------------------
            String query = title + " " + artist + " official music video";

            String url = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                    .queryParam("part", "snippet")
                    .queryParam("q", query)
                    .queryParam("type", "video")
                    .queryParam("maxResults", Math.max(1, candidatesPerSearch))
                    .queryParam("key", apiKey)
                    .build(false)   // <-- 인코딩 하지 않음!!
                    .toUriString();

            // ---------------------------------------------------------
            // 요청 로그 전체 출력
            // ---------------------------------------------------------
            log.warn("🔑 YOUTUBE API KEY = {}", apiKey);
            log.warn("🌐 YOUTUBE REQUEST URL = {}", url);

            String res = rest.getForObject(url, String.class);

            // 응답 RAW 전체 출력
            log.warn("📩 YOUTUBE RESPONSE RAW = {}", res);

            if (res == null) {
                log.error("❌ YouTube API returned null response");
                return null;
            }

            JSONObject json = new JSONObject(res);

            if (json.has("error")) {
                log.error("❌ YouTube API Error: {}", json.getJSONObject("error").toString());
                return null;
            }

            JSONArray items = json.optJSONArray("items");
            if (items == null || items.isEmpty()) {
                log.warn("🔍 No YouTube results for '{}' '{}'", title, artist);
                return null;
            }

            return pickBest(items, title, artist, cacheKey);

        } catch (HttpStatusCodeException e) {
            log.error("❌ YouTube HTTP Error {} / {}", e.getStatusCode(), e.getResponseBodyAsString());
            return null;

        } catch (Exception e) {
            log.error("❌ YouTube exception: {}", e.toString(), e);
            return null;
        }
    }

    // ---------------------------------------------------------
    // 최종 선택 (지니어스 스코어링 적용)
    // ---------------------------------------------------------
    private String pickBest(JSONArray items, String title, String artist, String cacheKey) {
        String wantTitle = norm(title);
        String wantArtist = norm(artist);

        JSONObject bestItem = null;
        double bestScore = -999;

        for (int i = 0; i < items.length(); i++) {

            JSONObject item = items.getJSONObject(i);
            JSONObject snippet = item.getJSONObject("snippet");

            String vId = item.getJSONObject("id").optString("videoId", "");
            String vTitle = norm(snippet.optString("title", ""));
            String chName = norm(snippet.optString("channelTitle", ""));

            double s = 0.0;

            // 1) 아티스트 정합성
            if (!wantArtist.isEmpty()) {

                if (chName.equals(wantArtist) || vTitle.contains(wantArtist))
                    s += 0.60;

                else if (chName.contains(wantArtist) || wantArtist.contains(chName))
                    s += 0.45;

                else continue;
            }

            // 2) 제목 유사도
            if (vTitle.equals(wantTitle))
                s += 0.30;

            else if (vTitle.contains(wantTitle))
                s += 0.20;

            else continue;

            // 3) 노이즈 패널티
            String noisy = snippet.optString("title", "").toLowerCase();
            boolean isOfficial = chName.contains("official") || chName.contains("vevo") || chName.endsWith("topic");

            // LIVE 관련 처리
            if (noisy.matches(".*\\blive\\b.*")) {
                if (isOfficial) {
                    // 공식 LIVE 영상은 패널티 제거 + 약간 보정
                    s += 0.05;
                } else {
                    s -= 0.40;
                }
            }

            // 그 외 노이즈 (항상 강패널티)
            if (noisy.matches(".*\\b(cover|remix|nightcore|sped up|lyrics|fancam|practice|dance)\\b.*"))
                s -= 0.40;



            // 공식성 보정
            if (chName.contains("official") || chName.contains("vevo"))
                s += 0.20;
            if (noisy.contains("official") || noisy.contains("mv"))
                s += 0.20;

            if (s > bestScore) {
                bestScore = s;
                bestItem = item;
            }

            if (s >= earlyStopScore) break;
        }

        if (bestItem != null) {
            String vid = bestItem.getJSONObject("id").optString("videoId", null);
            cache.put(cacheKey, vid);
            log.info("🎬 Selected YouTube Video = {}", vid);
            return vid;
        }

        log.warn("⚠ No suitable YouTube video found for {} / {}", title, artist);
        return null;
    }

    // ---------------------------------------------------------
    // 정규화
    // ---------------------------------------------------------
    private static String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);

        x = Normalizer.normalize(x, Normalizer.Form.NFKC);
        x = x.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");
        x = x.replaceAll("\\b(feat\\.|ft\\.|with)\\b.*", " ");
        x = x.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣぁ-ゔァ-ヴー一-龯々〆〤\\s]", " ");
        x = x.replaceAll("\\s+", " ").trim();

        return x;
    }

    // ---------------------------------------------------------
    // Helper URL
    // ---------------------------------------------------------
    public static String watchUrl(String id) {
        return id == null ? null : "https://www.youtube.com/watch?v=" + id;
    }

    public static String embedUrl(String id) {
        return id == null ? null : "https://www.youtube.com/embed/" + id;
    }

    public static String thumbnailUrl(String id) {
        return id == null ? null : "https://img.youtube.com/vi/" + id + "/hqdefault.jpg";
    }
}
