package org.example.apispring.recommend.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.recommend.domain.Song;
import org.example.apispring.recommend.domain.SongRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
@Service
public class YoutubeService {

    // ---------------------------------------------------------
    // 외부 설정값
    // ---------------------------------------------------------
    @Value("${cloudify.youtube.apiKey:}")
    private String apiKey;

    @Value("${cloudify.youtube.candidatesPerSearch:8}")
    private int candidatesPerSearch;

    @Value("${cloudify.youtube.earlyStopScore:0.90}")
    private double earlyStopScore;

    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";

    private final RestTemplate rest = new RestTemplate();

    private final SongRepository songRepository;

    private static final int BATCH_SIZE = 50;

    // ---------------------------------------------------------
    // 국내 공식 채널 화이트리스트
    // ---------------------------------------------------------
    private static final List<String> DOMESTIC_OFFICIAL_CHANNELS = Arrays.asList(
            "1thek", "원더케이", "stone music", "genie", "kakao", "loen",
            "bighit", "hybe", "smtown", "jyp", "yg", "starship"
    );


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
        log.info("🔥 YouTubeService UTF-8 initialized");
    }

    // ---------------------------------------------------------
    // 비동기 검색 지원
    // ---------------------------------------------------------
    public CompletableFuture<String> fetchVideoIdAsync(String title, String artist) {
        return CompletableFuture.supplyAsync(() -> fetchVideoIdBySearch(title, artist));
    }

    // ---------------------------------------------------------
    // YouTube 검색 API 호출 및 후보 결과 가져오기
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

        try {
            // ---------------------------------------------------------
            // 검색 쿼리 구성 (제목 + 아티스트 + official music video)
            // ---------------------------------------------------------
            String query = title + " " + artist + " official music video";

            String url = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                    .queryParam("part", "snippet")
                    .queryParam("q", query)
                    .queryParam("type", "video")
                    .queryParam("maxResults", Math.max(1, candidatesPerSearch))
                    .queryParam("key", apiKey)
                    .build(false)
                    .toUriString();

            // 요청 URL 로그
            log.warn("🌐 YOUTUBE REQUEST URL = {}", url);

            // ---------------------------------------------------------
            // API 호출
            // ---------------------------------------------------------
            String res = rest.getForObject(url, String.class);

            // 원본 응답 로그
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

            // ---------------------------------------------------------
            // 후보 중 최고 점수 영상 선택
            // ---------------------------------------------------------
            return pickBest(items, title, artist);

        } catch (HttpStatusCodeException e) {
            log.error("❌ YouTube HTTP Error {} / {}", e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.error("❌ YouTube exception: {}", e.toString(), e);
            return null;
        }
    }

    // ---------------------------------------------------------
    // 후보 영상 중 최고 점수 영상 선택
    // 점수 산정: 아티스트 일치, 제목 유사도, 공식성, LIVE/커버 패널티 등
    // ---------------------------------------------------------
    private String pickBest(JSONArray items, String title, String artist) {
        String wantTitle = normalizeForSearch(title);
        List<String> wantArtists = splitArtists(artist); // 아티스트 분리 (feat, &, / 등)

        JSONObject bestItem = null;
        double bestScore = -999;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject snippet = item.getJSONObject("snippet");

            String vId = item.getJSONObject("id").optString("videoId", "");
            String vTitle = normalizeForSearch(snippet.optString("title", ""));
            String chName = normalizeForSearch(snippet.optString("channelTitle", ""));

            double s = 0.0;

            // ---------------------------------------------------------
            // 아티스트 정합성 점수
            // - 정확 일치: +0.60
            // - 부분 포함: +0.45
            // ---------------------------------------------------------
            for (String a : wantArtists) {
                if (matchArtists(chName, a) || vTitle.contains(a)) s += 0.60;
                else if (chName.contains(a) || a.contains(chName)) s += 0.45;
            }

            // ---------------------------------------------------------
            // 제목 유사도 점수
            // - 완전 일치: +0.30
            // - 포함만 되어도: +0.20
            // ---------------------------------------------------------
            if (matchTitles(vTitle, wantTitle)) s += 0.30;
            else if (vTitle.contains(wantTitle)) s += 0.20;

            String noisy = snippet.optString("title", "").toLowerCase();

            // ---------------------------------------------------------
            // 공식 채널 판단
            // - 영어 official, vevo, topic
            // - 국내 화이트리스트 채널: +0.10
            // ---------------------------------------------------------
            boolean isOfficial = snippet.optString("channelTitle", "").toLowerCase().contains("official")
                    || snippet.optString("channelTitle", "").toLowerCase().contains("vevo")
                    || snippet.optString("channelTitle", "").toLowerCase().endsWith("topic");

            for (String c : DOMESTIC_OFFICIAL_CHANNELS) {
                if (snippet.optString("channelTitle", "").toLowerCase().contains(c.toLowerCase())) {
                    s += 0.10;
                    isOfficial = true;
                }
            }

            // ---------------------------------------------------------
            // LIVE 관련 처리
            // - 공식 LIVE: 점수 변동 없음
            // - 비공식 LIVE: -0.40
            // ---------------------------------------------------------
            if (noisy.matches(".*\\blive\\b.*")) {
                if (isOfficial) s += 0.00; // 점수 변동 없음
                else s -= 0.40;
            }

            // ---------------------------------------------------------
            // 노이즈 키워드 패널티
            // cover, remix, nightcore, sped up, lyrics, fancam, practice, dance, performance
            // ---------------------------------------------------------
            if (noisy.matches(".*\\b(cover|remix|nightcore|sped up|lyrics|fancam|practice|dance|performance)\\b.*")) s -= 0.40;

            // ---------------------------------------------------------
            // 제목/채널 내 official/MV 키워드 보정
            // ---------------------------------------------------------
            if (snippet.optString("channelTitle", "").toLowerCase().contains("official") ||
                    snippet.optString("channelTitle", "").toLowerCase().contains("vevo")) s += 0.20;
            if (noisy.contains("official") || noisy.contains("mv")) s += 0.20;

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
            log.info("🎬 Selected YouTube Video = {}", vid);
            return vid;
        }

        log.warn("⚠ No suitable YouTube video found for {} / {}", title, artist);
        return null;
    }

    // ---------------------------------------------------------
    // 문자열 정규화
    // - 대문자→소문자
    // - 악센트 제거
    // - 괄호, feat 등 제거
    // - 특수문자 제거, 공백 정리
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
    // - & / , and feat. 등 구분
    // - normalizeForSearch 적용
    // ---------------------------------------------------------
    private List<String> splitArtists(String artist) {
        if (artist == null) return Collections.emptyList();
        return Arrays.stream(artist.split("\\s*(?:&|/|,|and|feat\\.?|ft\\.?)\\s*"))
                .map(String::trim)
                .filter(a -> !a.isEmpty())
                .map(YoutubeService::normalizeForSearch)
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

    // ---------------------------------------------------------
    // YouTube URL 헬퍼
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

    @Transactional
    public void fillVideoIds() {
        int page = 0;
        List<Song> batch;
        do {
            batch = songRepository.findAllByVideoIdIsNull(PageRequest.of(page, BATCH_SIZE)).getContent();
            for (Song song : batch) {
                try {
                    String videoId = fetchVideoIdBySearch(song.getTitle(), song.getArtist());
                    if (videoId != null && !videoId.isBlank()) {
                        song.updateVideoId(videoId);
                        songRepository.save(song);
                        log.info("📌 VIDEO_ID 저장: {} - {} -> {}", song.getTitle(), song.getArtist(), videoId);
                    }
                } catch (Exception e) {
                    log.error("❌ 저장실패 {} - {}", song.getTitle(), song.getArtist(), e.getMessage());
                }
            }
            page++;
        } while (!batch.isEmpty());
    }
}