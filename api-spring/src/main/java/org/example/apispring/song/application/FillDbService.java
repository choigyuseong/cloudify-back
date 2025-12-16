package org.example.apispring.song.application;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.song.application.dto.GeniusAlbumImageFillResultDto;
import org.example.apispring.song.application.dto.YoutubeAudioFillResultDto;
import org.example.apispring.song.application.dto.YoutubeVideoThumbFillResultDto;
import org.example.apispring.song.domain.Song;
import org.example.apispring.song.domain.SongRepository;
import org.example.apispring.song.web.GeniusClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FillDbService {

    private static final int GENIUS_DEFAULT_LIMIT = 20;
    private static final int GENIUS_MAX_LIMIT = 50; // 너무 크게 잡으면 또 504 가능

    private static final int YOUTUBE_BATCH_SIZE = 10;
    private static final int YOUTUBE_CONCURRENCY = 4;
    private static final int YOUTUBE_CALL_TIMEOUT_SEC = 9;

    private final SongRepository songRepository;
    private final GeniusClient geniusClient;

    private final YoutubeVideoIdSearchService youtubeVideoIdSearchService;
    private final YoutubeAudioIdSearchService youtubeAudioIdSearchService;

    private final ExecutorService youtubeExecutor = Executors.newFixedThreadPool(YOUTUBE_CONCURRENCY);

    @PreDestroy
    public void shutdown() {
        youtubeExecutor.shutdown();
    }

    // =========================================================
    // 1) GENIUS - Album Image Fill
    //   - 한 번 호출에 limit(기본 20)만 처리
    //   - no_hits면 쿼리 변형으로 재시도
    //   - 재시도 후에도 전부 no_hits면 trash 저장
    //   - hits는 있는데 NO_USABLE_ART만 계속이면 trash로 확정하지 않고 skip
    // =========================================================
    @Transactional
    public GeniusAlbumImageFillResultDto fillAlbumImagesFromGenius() {
        return fillAlbumImagesFromGenius(GENIUS_DEFAULT_LIMIT);
    }

    @Transactional
    public GeniusAlbumImageFillResultDto fillAlbumImagesFromGenius(int limit) {
        final String rid = shortRid();
        int effectiveLimit = clamp(limit, 1, GENIUS_MAX_LIMIT);

        List<Song> batch = songRepository.findSongsWithoutAlbumImage(PageRequest.of(0, effectiveLimit));
        if (batch.isEmpty()) {
            return new GeniusAlbumImageFillResultDto(effectiveLimit, 0, 0, 0, 0, 0);
        }

        int success = 0;
        int trash = 0;
        int transientSkip = 0;
        int failures = 0;

        List<Song> toSave = new ArrayList<>(batch.size());

        for (Song song : batch) {
            final String songId = song.getId();

            String artist = nullToEmpty(song.getArtist()).trim();
            String title  = nullToEmpty(song.getTitle()).trim();

            // 데이터 결함은 trash로 확정 (이 케이스는 변형해도 소용 없음)
            if ((artist + title).isBlank()) {
                song.updateAlbumImageUrl("trash");
                toSave.add(song);
                trash++;
                continue;
            }

            List<String> queryVariants = buildGeniusQueryVariants(artist, title);

            boolean sawAnyHits = false;
            GeniusPick bestPick = null;
            String usedQuery = null;

            boolean transientErrorForThisSong = false;

            for (String query : queryVariants) {
                ResponseEntity<String> res;
                try {
                    res = geniusClient.search(query);
                } catch (BusinessException be) {
                    // 설정/인증/쿼터 계열은 즉시 중단하는 편이 안전
                    if (be.errorCode() == ErrorCode.GENIUS_API_TOKEN_MISSING
                            || be.errorCode().name().equals("GENIUS_AUTH_FAILED")
                            || be.errorCode().name().equals("GENIUS_BAD_REQUEST")
                            || be.errorCode() == ErrorCode.GENIUS_QUOTA_EXCEEDED) {
                        throw be;
                    }
                    // 나머지는 일단 해당 곡은 스킵
                    transientErrorForThisSong = true;
                    failures++;
                    break;
                } catch (Exception e) {
                    transientErrorForThisSong = true;
                    failures++;
                    break;
                }

                if (res == null || res.getBody() == null) {
                    transientErrorForThisSong = true;
                    failures++;
                    break;
                }

                int sc = res.getStatusCode().value();
                if (sc < 200 || sc >= 300) {
                    // 여기서도 status 분기하고 싶으면 GeniusClient에서 처리(권장)
                    transientErrorForThisSong = true;
                    failures++;
                    break;
                }

                JSONObject root;
                try {
                    root = new JSONObject(res.getBody());
                } catch (Exception e) {
                    transientErrorForThisSong = true;
                    failures++;
                    break;
                }

                JSONObject resp = root.optJSONObject("response");
                if (resp == null) {
                    transientErrorForThisSong = true;
                    failures++;
                    break;
                }

                JSONArray hits = resp.optJSONArray("hits");
                int hitCount = (hits == null ? 0 : hits.length());

                // 핵심: no_hits면 “다음 쿼리 변형”으로 재시도
                if (hitCount == 0) {
                    continue;
                }

                sawAnyHits = true;

                GeniusPick pick = selectAlbumImage(hits, title, artist, rid, songId);

                // usable art를 얻으면 즉시 종료
                if (!isBlank(pick.url())) {
                    bestPick = pick;
                    usedQuery = query;
                    break;
                }

                // hits는 있는데 선택 실패(NO_USABLE_ART 등) → 다른 쿼리 변형에서 개선될 여지 있으니 계속
            }

            if (transientErrorForThisSong) {
                // 이 곡은 업데이트하지 않고 유지(NULL)
                transientSkip++;
                continue;
            }

            if (bestPick != null && !isBlank(bestPick.url())) {
                song.updateAlbumImageUrl(bestPick.url());
                toSave.add(song);
                success++;
                continue;
            }

            // 여기까지 왔다는 건 “최종적으로 usable url을 못 얻음”
            // - 재시도 내내 no_hits만이었다면 trash 확정
            // - hits는 있었는데 NO_USABLE_ART만 나온 경우는 trash 확정하지 않고 skip
            if (!sawAnyHits) {
                song.updateAlbumImageUrl("trash");
                toSave.add(song);
                trash++;
            } else {
                transientSkip++;
            }
        }

        if (!toSave.isEmpty()) {
            songRepository.saveAll(toSave);
        }

        return new GeniusAlbumImageFillResultDto(
                effectiveLimit,
                batch.size(),
                success,
                trash,
                transientSkip,
                failures
        );
    }

    private List<String> buildGeniusQueryVariants(String artist, String title) {
        // 중복 제거 + 순서 보장
        LinkedHashSet<String> qs = new LinkedHashSet<>();

        String original = (artist + " " + title).trim();
        qs.add(original);

        String cleanTitle = simplifyTitleForQuery(title);
        String v2 = (artist + " " + cleanTitle).trim();
        String v3 = (cleanTitle + " " + artist).trim();

        qs.add(v2);
        qs.add(v3);
        qs.add(cleanTitle);

        return qs.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    private String simplifyTitleForQuery(String title) {
        if (title == null) return "";
        String t = title;

        // 괄호/대괄호/중괄호 안의 정보 제거 (feat/prod 정보가 여기 들어있는 경우가 많음)
        t = t.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");

        // 뒤에 붙는 feat/prod 류 제거
        t = t.replaceAll("(?i)\\b(prod\\.|produced by|feat\\.|ft\\.|featuring|with)\\b.*", " ");

        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private GeniusPick selectAlbumImage(JSONArray hits, String title, String artist, String rid, String songId) {
        String wantTitle  = norm(title);
        String wantArtist = norm(artist);

        String bestArt = null;
        double bestScore = -999.0;

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;

            JSONObject result = hit.optJSONObject("result");
            if (result == null) continue;

            String art = pickArtUrl(result);
            if (art == null || isDefaultGeniusImage(art)) continue;

            String primaryArtist = norm(primaryArtistName(result));
            String rTitle = norm(result.optString("title", ""));
            String fullTitle = norm(result.optString("full_title", ""));

            double s = 0.0;

            if (!wantArtist.isEmpty() && !primaryArtist.isEmpty()) {
                if (primaryArtist.equals(wantArtist)) s += 0.60;
                else if (primaryArtist.contains(wantArtist) || wantArtist.contains(primaryArtist)) s += 0.45;
                else s -= 0.20;
            }

            if (!wantTitle.isEmpty()) {
                if (rTitle.equals(wantTitle)) s += 0.30;
                else if (rTitle.contains(wantTitle) || fullTitle.contains(wantTitle)) s += 0.20;
            }

            String noisy = (rTitle + " " + fullTitle);
            if (noisy.matches(".*\\b(live|cover|remix|nightcore|sped up|lyrics)\\b.*")) s -= 0.25;

            if (s > bestScore) {
                bestScore = s;
                bestArt = art;
            }
        }

        if (!isBlank(bestArt)) return new GeniusPick(bestArt, bestScore, "SELECTED_BEST");

        return new GeniusPick(null, bestScore, "NO_USABLE_ART");
    }

    private record GeniusPick(String url, double bestScore, String reason) {}

    private static String shortRid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }

    private String pickArtUrl(JSONObject result) {
        if (result == null) return null;
        String[] keys = {"song_art_image_url", "song_art_image_thumbnail_url", "header_image_url"};
        for (String k : keys) {
            Object raw = result.opt(k);
            if (raw == null || raw == JSONObject.NULL) continue;
            String v = String.valueOf(raw);
            if (v.isBlank() || "null".equalsIgnoreCase(v)) continue;
            if (isHttp(v)) return v;
        }
        return null;
    }

    private String primaryArtistName(JSONObject result) {
        JSONObject pa = (result == null) ? null : result.optJSONObject("primary_artist");
        if (pa == null) return "";
        Object raw = pa.opt("name");
        if (raw == null || raw == JSONObject.NULL) return "";
        String name = String.valueOf(raw);
        return "null".equalsIgnoreCase(name) ? "" : name;
    }

    private boolean isDefaultGeniusImage(String url) {
        if (url == null) return true;
        return url.contains("/images/default") || url.contains("/default_thumb");
    }

    private boolean isHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);
        x = Normalizer.normalize(x, Normalizer.Form.NFKC);
        x = x.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");
        x = x.replaceAll("\\b(feat\\.|ft\\.|with)\\b.*", " ");
        x = x.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣぁ-ゔァ-ヴー一-龯々〆〤\\s']", " ");
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }

    // ===========================
    // 이하 YouTube 로직은 기존 그대로 두면 됩니다
    // ===========================
    public YoutubeVideoThumbFillResultDto fillYoutubeVideoIdAndThumbnail() { /* ... 기존 코드 ... */ return null; }
    public YoutubeAudioFillResultDto fillYoutubeAudioId() { /* ... 기존 코드 ... */ return null; }
}
