package org.example.apispring.recommend.service.youtube;

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
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class YouTubeServiceLyrics {

    // ---------------------------------------------------------
    // 외부 설정값
    // ---------------------------------------------------------
    @Value("${cloudify.youtube.apiKey:}")
    private String apiKey;

    @Value("${cloudify.youtube.candidatesPerSearch:8}")
    private int candidatesPerSearch;

    /**
     * audio(lyrics) 모드에서 너무 높은 점수가 나오면 바로 early stop.
     * (기본값은 videoId 로직과 동일하게 둠)
     */
    @Value("${cloudify.youtube.lyricsEarlyStopScore:0.90}")
    private double earlyStopScore;

    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";

    private final RestTemplate rest = new RestTemplate();
    private final YouTubeCache cache;

    public YouTubeServiceLyrics(YouTubeCache cache) {
        this.cache = cache;
    }

    // ---------------------------------------------------------
    // RestTemplate UTF-8 인코딩 강제 설정
    // ---------------------------------------------------------
    @PostConstruct
    public void init() {
        rest.setRequestFactory(new SimpleClientHttpRequestFactory());
        rest.getMessageConverters().stream()
                .filter(c -> c instanceof StringHttpMessageConverter)
                .forEach(c -> ((StringHttpMessageConverter) c)
                        .setDefaultCharset(StandardCharsets.UTF_8));
        log.info("🔥 YouTubeServiceLyrics UTF-8 initialized");
    }

    // ---------------------------------------------------------
    // 비동기 검색 지원
    // ---------------------------------------------------------
    public CompletableFuture<String> fetchAudioIdAsync(String title, String artist) {
        return CompletableFuture.supplyAsync(() -> fetchAudioIdBySearch(title, artist));
    }

    // ---------------------------------------------------------
    // YouTube 검색 API 호출 및 후보 결과 가져오기 (LYRICS / AUDIO 용)
    // ---------------------------------------------------------
    public String fetchAudioIdBySearch(String title, String artist) {
        if (title == null || artist == null) {
            log.warn("❌ Title or artist is null");
            return null;
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.error("❌ Missing YouTube API KEY");
            return null;
        }

        // 캐시 키 생성 및 조회 (videoId와 구분하기 위해 prefix 부여)
        final String cacheKey = ("lyrics:" + title + "|" + artist).toLowerCase(Locale.ROOT);
        String cached = cache.get(cacheKey);
        if (cached != null) {
            log.info("⚡ YouTube lyrics cache hit: {} -> {}", cacheKey, cached);
            return cached;
        }

        try {
            // ---------------------------------------------------------
            // 검색 쿼리 구성 (제목 + 아티스트 + lyrics)
            // - MV 인트로를 피하기 위한 "가사/음원형" 영상 후보를 찾는 목적
            // ---------------------------------------------------------
            String query = title + " " + artist + " lyrics";

            String url = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                    .queryParam("part", "snippet")
                    .queryParam("q", query)
                    .queryParam("type", "video")
                    .queryParam("maxResults", Math.max(1, candidatesPerSearch))
                    .queryParam("key", apiKey)
                    .build(false)
                    .toUriString();

            // 요청 URL 로그
            log.warn("🌐 YOUTUBE LYRICS REQUEST URL = {}", url);

            // ---------------------------------------------------------
            // API 호출
            // ---------------------------------------------------------
            String res = rest.getForObject(url, String.class);

            // 원본 응답 로그
            log.warn("📩 YOUTUBE LYRICS RESPONSE RAW = {}", res);

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
                log.warn("🔍 No YouTube lyrics results for '{}' '{}'", title, artist);
                return null;
            }

            // ---------------------------------------------------------
            // 후보 중 최고 점수 영상 선택 (LYRICS 전용 스코어링)
            // ---------------------------------------------------------
            return pickBestLyrics(items, title, artist, cacheKey);

        } catch (HttpStatusCodeException e) {
            log.error("❌ YouTube HTTP Error {} / {}", e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.error("❌ YouTube exception: {}", e.toString(), e);
            return null;
        }
    }

    // ---------------------------------------------------------
    // 후보 영상 중 최고 점수 영상 선택 (LYRICS / AUDIO 용)
    //
    // 목표:
    // - "가사/음원형" 영상에 높은 점수
    // - MV/퍼포먼스/댄스/라이브/티저 같은 영상성 컨텐츠는 패널티
    //
    // NOTE:
    // - 이 로직은 "공식 MV"를 찾는 videoId 로직과 목적이 다르므로,
    //   공식성 가산(official/mv) 같은 룰을 최소화하고, lyrics 키워드를 우선한다.
    // ---------------------------------------------------------
    private String pickBestLyrics(JSONArray items, String title, String artist, String cacheKey) {
        String wantTitle = normalizeForSearch(title);
        List<String> wantArtists = splitArtists(artist);

        JSONObject bestItem = null;
        double bestScore = -999;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject snippet = item.getJSONObject("snippet");

            String vId = item.getJSONObject("id").optString("videoId", "");
            String vTitleNorm = normalizeForSearch(snippet.optString("title", ""));
            String chNameNorm = normalizeForSearch(snippet.optString("channelTitle", ""));

            String noisy = snippet.optString("title", "").toLowerCase(Locale.ROOT);

            double s = 0.0;

            // ---------------------------------------------------------
            // 1) 아티스트 정합성 (너무 엄격하진 않게)
            // ---------------------------------------------------------
            for (String a : wantArtists) {
                if (matchArtists(chNameNorm, a) || vTitleNorm.contains(a)) s += 0.45;
                else if (chNameNorm.contains(a) || a.contains(chNameNorm)) s += 0.25;
            }

            // ---------------------------------------------------------
            // 2) 제목 정합성
            // ---------------------------------------------------------
            if (matchTitles(vTitleNorm, wantTitle)) s += 0.25;
            else if (vTitleNorm.contains(wantTitle)) s += 0.15;

            // ---------------------------------------------------------
            // 3) "lyrics/lyric/가사" 보너스 (핵심)
            // ---------------------------------------------------------
            boolean hasLyrics =
                    noisy.contains("lyrics") ||
                            noisy.contains("lyric") ||
                            noisy.contains("가사");

            if (hasLyrics) s += 0.35;
            else s -= 0.15; // lyrics 검색인데도 제목에 없으면 약간 감점

            // ---------------------------------------------------------
            // 4) 영상성 키워드 패널티 (MV/퍼포먼스/댄스/라이브/티저 등)
            // ---------------------------------------------------------
            if (noisy.matches(".*\\b(mv|music video)\\b.*")) s -= 0.60;
            if (noisy.matches(".*\\b(performance|dance|practice)\\b.*")) s -= 0.50;
            if (noisy.matches(".*\\b(live|fancam)\\b.*")) s -= 0.50;
            if (noisy.matches(".*\\b(teaser|trailer)\\b.*")) s -= 0.80;

            // ---------------------------------------------------------
            // 5) 노이즈 키워드 패널티 (커버/리믹스/속도변형 등)
            // ---------------------------------------------------------
            if (noisy.matches(".*\\b(cover|remix|nightcore|sped up|slowed|8d)\\b.*")) s -= 0.50;

            // ---------------------------------------------------------
            // 6) (선택) Topic 채널은 오디오형일 가능성이 높아 소폭 가산
            // ---------------------------------------------------------
            if (snippet.optString("channelTitle", "").toLowerCase(Locale.ROOT).endsWith("topic")) s += 0.15;

            // ---------------------------------------------------------
            // 최고 점수 후보 선택
            // ---------------------------------------------------------
            if (s > bestScore) {
                bestScore = s;
                bestItem = item;
            }

            if (s >= earlyStopScore) break;
        }

        if (bestItem != null) {
            String vid = bestItem.getJSONObject("id").optString("videoId", null);
            cache.put(cacheKey, vid);
            log.info("🎧 Selected YouTube Audio(Lyrics) Video = {}", vid);
            return vid;
        }

        log.warn("⚠ No suitable YouTube lyrics video found for {} / {}", title, artist);
        return null;
    }

    // ---------------------------------------------------------
    // 문자열 정규화
    // ---------------------------------------------------------
    private static String normalizeForSearch(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        x = x.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");
        x = x.replaceAll("\\b(feat\\.|ft\\.|with)\\b.*", " ");
        x = x.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣぁ-ゔァ-ヴー一-龯々〆〤\\s']", " ");
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }

    // ---------------------------------------------------------
    // 아티스트 문자열 분리
    // ---------------------------------------------------------
    private List<String> splitArtists(String artist) {
        if (artist == null) return Collections.emptyList();
        return Arrays.stream(artist.split("\\s*(?:&|/|,|and|feat\\.?|ft\\.?)\\s*"))
                .map(String::trim)
                .filter(a -> !a.isEmpty())
                .map(YouTubeServiceLyrics::normalizeForSearch)
                .toList();
    }

    // ---------------------------------------------------------
    // 제목 비교
    // ---------------------------------------------------------
    private boolean matchTitles(String queryTitle, String targetTitle) {
        return normalizeForSearch(queryTitle).equals(normalizeForSearch(targetTitle));
    }

    // ---------------------------------------------------------
    // 아티스트 비교
    // ---------------------------------------------------------
    private boolean matchArtists(String queryArtist, String targetArtist) {
        return normalizeForSearch(queryArtist).equals(normalizeForSearch(targetArtist));
    }
}
