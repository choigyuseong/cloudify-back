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

    private static final int GENIUS_BATCH_SIZE = 20;

    private static final int YOUTUBE_BATCH_SIZE = 10;
    private static final int YOUTUBE_CONCURRENCY = 4;
    private static final int YOUTUBE_CALL_TIMEOUT_SEC = 9;

    private final SongRepository songRepository;
    private final GeniusClient geniusClient;

    private final YoutubeVideoIdSearchService youtubeVideoIdSearchService;
    private final YoutubeAudioIdSearchService youtubeAudioIdSearchService;
    private final SongQueryNormalizationService songQueryNormalizationService;

    private final ExecutorService youtubeExecutor = Executors.newFixedThreadPool(YOUTUBE_CONCURRENCY);

    @PreDestroy
    public void shutdown() {
        youtubeExecutor.shutdown();
    }

    @Transactional
    public GeniusAlbumImageFillResultDto fillAlbumImagesFromGenius(int limit) {
        int batchSize = Math.max(1, Math.min(limit, GENIUS_BATCH_SIZE));
        final String rid = shortRid();

        List<Song> batch = songRepository.findSongsWithoutAlbumImage(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            log.info("[GeniusFill:{}] done(no_candidates) limit={}", rid, batchSize);
            return new GeniusAlbumImageFillResultDto(batchSize, 0, 0, 0, 0, 0);
        }

        int updated = 0;
        int success = 0;
        int trash = 0;
        int transientSkip = 0;

        List<Song> toSave = new ArrayList<>(batch.size());

        for (Song song : batch) {
            final String songId = song.getId();

            String artist = nullToEmpty(song.getArtist()).trim();
            String title = nullToEmpty(song.getTitle()).trim();
            String query1 = (artist + " " + title).trim();

            if (query1.isBlank()) {
                song.updateAlbumImageUrl("trash");
                toSave.add(song);
                updated++;
                trash++;
                continue;
            }

            HitsOutcome o1 = fetchHits(query1, rid, songId);
            if (o1.type == HitsOutcomeType.TRANSIENT_SKIP) {
                transientSkip++;
                continue;
            }

            if (o1.type == HitsOutcomeType.OK) {
                GeniusPick pick = selectAlbumImage(o1.hits, title, artist, rid, songId);
                if (isBlank(pick.url())) {
                    transientSkip++;
                    continue;
                }
                song.updateAlbumImageUrl(pick.url());
                toSave.add(song);
                updated++;
                success++;
                continue;
            }

            String cleanTitle = songQueryNormalizationService.cleanTitle(title);
            if (cleanTitle.isBlank() || cleanTitle.equals(title)) {
                song.updateAlbumImageUrl("trash");
                toSave.add(song);
                updated++;
                trash++;
                continue;
            }

            String query2 = (artist + " " + cleanTitle).trim();
            if (query2.isBlank()) {
                song.updateAlbumImageUrl("trash");
                toSave.add(song);
                updated++;
                trash++;
                continue;
            }

            HitsOutcome o2 = fetchHits(query2, rid, songId);
            if (o2.type == HitsOutcomeType.TRANSIENT_SKIP) {
                transientSkip++;
                continue;
            }

            if (o2.type == HitsOutcomeType.NO_HITS) {
                song.updateAlbumImageUrl("trash");
                toSave.add(song);
                updated++;
                trash++;
                continue;
            }

            GeniusPick pick2 = selectAlbumImage(o2.hits, cleanTitle, artist, rid, songId);
            if (isBlank(pick2.url())) {
                transientSkip++;
                continue;
            }

            song.updateAlbumImageUrl(pick2.url());
            toSave.add(song);
            updated++;
            success++;
        }

        if (!toSave.isEmpty()) {
            songRepository.saveAll(toSave);
        }

        log.info("[GeniusFill:{}] done limit={} fetched={} updated={} success={} trash={} transientSkip={}",
                rid, batchSize, batch.size(), updated, success, trash, transientSkip);

        return new GeniusAlbumImageFillResultDto(
                batchSize,
                batch.size(),
                updated,
                success,
                trash,
                transientSkip);
    }

    private enum HitsOutcomeType { OK, NO_HITS, TRANSIENT_SKIP }

    private record HitsOutcome(HitsOutcomeType type, JSONArray hits) {}

    private HitsOutcome fetchHits(String query, String rid, String songId) {
        ResponseEntity<String> res;
        try {
            res = geniusClient.search(query);
        } catch (BusinessException be) {
            if (be.errorCode() == ErrorCode.GENIUS_API_TOKEN_MISSING) throw be;
            log.warn("[GeniusFill:{}] songId={} transient_skip reason=business_exception code={} msg={} query='{}'",
                    rid, songId, be.errorCode().name(), be.getMessage(), query);
            return new HitsOutcome(HitsOutcomeType.TRANSIENT_SKIP, null);
        } catch (Exception e) {
            log.warn("[GeniusFill:{}] songId={} transient_skip reason=exception err={} msg={} query='{}'",
                    rid, songId, e.getClass().getSimpleName(), e.getMessage(), query);
            return new HitsOutcome(HitsOutcomeType.TRANSIENT_SKIP, null);
        }

        if (res == null || res.getBody() == null) {
            log.warn("[GeniusFill:{}] songId={} transient_skip reason=null_response status={}",
                    rid, songId, (res == null ? "null" : res.getStatusCode()));
            return new HitsOutcome(HitsOutcomeType.TRANSIENT_SKIP, null);
        }

        int sc = res.getStatusCode().value();
        if (sc < 200 || sc >= 300) {
            log.warn("[GeniusFill:{}] songId={} transient_skip reason=non_2xx status={} bodyPrefix={} query='{}'",
                    rid, songId, sc, safePrefix(res.getBody(), 200), query);
            return new HitsOutcome(HitsOutcomeType.TRANSIENT_SKIP, null);
        }

        JSONObject root;
        try {
            root = new JSONObject(res.getBody());
        } catch (Exception e) {
            log.warn("[GeniusFill:{}] songId={} transient_skip reason=json_parse_fail err={} bodyPrefix={} query='{}'",
                    rid, songId, e.getClass().getSimpleName(), safePrefix(res.getBody(), 200), query);
            return new HitsOutcome(HitsOutcomeType.TRANSIENT_SKIP, null);
        }

        JSONObject resp = root.optJSONObject("response");
        if (resp == null) {
            log.warn("[GeniusFill:{}] songId={} transient_skip reason=missing_response_node bodyPrefix={} query='{}'",
                    rid, songId, safePrefix(res.getBody(), 200), query);
            return new HitsOutcome(HitsOutcomeType.TRANSIENT_SKIP, null);
        }

        JSONArray hits = resp.optJSONArray("hits");
        int hitCount = (hits == null ? 0 : hits.length());
        if (hitCount == 0) {
            return new HitsOutcome(HitsOutcomeType.NO_HITS, null);
        }

        return new HitsOutcome(HitsOutcomeType.OK, hits);
    }

    private GeniusPick selectAlbumImage(JSONArray hits, String title, String artist, String rid, String songId) {
        String wantTitle = songQueryNormalizationService.normalizeForMatch(title);
        String wantArtist = songQueryNormalizationService.normalizeForMatch(artist);

        String bestArt = null;
        double bestScore = -999.0;

        int considered = 0;
        int skippedDefault = 0;

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;

            JSONObject result = hit.optJSONObject("result");
            if (result == null) continue;

            String art = pickArtUrl(result);
            if (art == null || isDefaultGeniusImage(art)) {
                skippedDefault++;
                continue;
            }

            String primaryArtist = songQueryNormalizationService.normalizeForMatch(primaryArtistName(result));
            String rTitle = songQueryNormalizationService.normalizeForMatch(result.optString("title", ""));
            String fullTitle = songQueryNormalizationService.normalizeForMatch(result.optString("full_title", ""));

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

            considered++;

            if (log.isDebugEnabled()) {
                log.debug("[GeniusPick:{}] songId={} cand#{} score={} art={} pa='{}' title='{}'",
                        rid, songId, i, s, art, primaryArtist, rTitle);
            }

            if (s > bestScore) {
                bestScore = s;
                bestArt = art;
            }
        }

        if (!isBlank(bestArt)) {
            log.debug("[GeniusPick:{}] songId={} selected score={} considered={} skipDefault={}",
                    rid, songId, bestScore, considered, skippedDefault);
            return new GeniusPick(bestArt, bestScore, "SELECTED_BEST");
        }

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;

            JSONObject result = hit.optJSONObject("result");
            if (result == null) continue;

            String art = pickArtUrl(result);
            if (art == null || isDefaultGeniusImage(art)) continue;

            String primaryArtist = songQueryNormalizationService.normalizeForMatch(primaryArtistName(result));
            String rTitle = songQueryNormalizationService.normalizeForMatch(result.optString("title", ""));
            String fullTitle = songQueryNormalizationService.normalizeForMatch(result.optString("full_title", ""));

            double s = 0.0;

            if (!wantArtist.isEmpty() && !primaryArtist.isEmpty()) {
                if (primaryArtist.equals(wantArtist)) s += 0.60;
                else if (primaryArtist.contains(wantArtist) || wantArtist.contains(primaryArtist)) s += 0.45;
            }
            if (!wantTitle.isEmpty()) {
                if (rTitle.equals(wantTitle)) s += 0.30;
                else if (rTitle.contains(wantTitle) || fullTitle.contains(wantTitle)) s += 0.20;
            }

            if (s >= 0.20) {
                log.debug("[GeniusPick:{}] songId={} fallback_selected score={} art={}", rid, songId, s, art);
                return new GeniusPick(art, s, "SELECTED_FALLBACK");
            }
        }

        return new GeniusPick(null, bestScore, "NO_USABLE_ART");
    }

    private record GeniusPick(String url, double bestScore, String reason) {}

    public YoutubeVideoThumbFillResultDto fillYoutubeVideoIdAndThumbnail() {
        int thumbFilled = fillThumbnailOnlyBatch();
        int videoFilled = fillVideoIdAndThumbnailBatch();
        return new YoutubeVideoThumbFillResultDto(videoFilled, thumbFilled);
    }

    public YoutubeAudioFillResultDto fillYoutubeAudioId() {
        List<Song> batch = songRepository.findSongsWithMissingAudioId(PageRequest.of(0, YOUTUBE_BATCH_SIZE));
        if (batch.isEmpty()) return new YoutubeAudioFillResultDto(0);

        List<AudioLookup> lookups = batch.stream()
                .filter(s -> isBlank(s.getAudioId()))
                .filter(s -> !isBlank(s.getTitle()) && !isBlank(s.getArtist()))
                .map(s -> new AudioLookup(s.getId(), s.getTitle(), s.getArtist()))
                .toList();

        Map<String, CompletableFuture<String>> futures = new HashMap<>();
        for (AudioLookup l : lookups) {
            CompletableFuture<String> f = CompletableFuture
                    .supplyAsync(() -> youtubeAudioIdSearchService.findAudioId(l.title, l.artist), youtubeExecutor)
                    .completeOnTimeout(null, YOUTUBE_CALL_TIMEOUT_SEC, TimeUnit.SECONDS);
            futures.put(l.songId, f);
        }

        int audioFilled = 0;
        List<Song> toSave = new ArrayList<>();

        for (Song song : batch) {
            if (!isBlank(song.getAudioId())) continue;

            CompletableFuture<String> f = futures.get(song.getId());
            if (f == null) continue;

            String audioId;
            try {
                audioId = f.join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof BusinessException be && be.errorCode() == ErrorCode.YOUTUBE_API_KEY_MISSING) {
                    throw be;
                }
                continue;
            }

            if (isBlank(audioId)) continue;

            song.updateAudioId(audioId);
            toSave.add(song);
            audioFilled++;
        }

        if (!toSave.isEmpty()) songRepository.saveAll(toSave);

        return new YoutubeAudioFillResultDto(audioFilled);
    }

    private int fillThumbnailOnlyBatch() {
        List<Song> batch = songRepository.findSongsWithMissingThumbnailOnly(PageRequest.of(0, YOUTUBE_BATCH_SIZE));
        if (batch.isEmpty()) return 0;

        List<Song> toSave = new ArrayList<>();
        for (Song song : batch) {
            String videoId = song.getVideoId();
            if (isBlank(videoId)) continue;
            if (!isBlank(song.getThumbnailImageUrl())) continue;

            song.updateThumbnailImageUrl(buildYoutubeThumbnailUrl(videoId));
            toSave.add(song);
        }

        if (!toSave.isEmpty()) songRepository.saveAll(toSave);
        return toSave.size();
    }

    private int fillVideoIdAndThumbnailBatch() {
        List<Song> batch = songRepository.findSongsWithMissingVideoId(PageRequest.of(0, YOUTUBE_BATCH_SIZE));
        if (batch.isEmpty()) return 0;

        List<VideoLookup> lookups = batch.stream()
                .filter(s -> isBlank(s.getVideoId()))
                .filter(s -> !isBlank(s.getTitle()) && !isBlank(s.getArtist()))
                .map(s -> new VideoLookup(s.getId(), s.getTitle(), s.getArtist()))
                .toList();

        Map<String, CompletableFuture<String>> futures = new HashMap<>();
        for (VideoLookup l : lookups) {
            CompletableFuture<String> f = CompletableFuture
                    .supplyAsync(() -> youtubeVideoIdSearchService.findVideoId(l.title, l.artist), youtubeExecutor)
                    .completeOnTimeout(null, YOUTUBE_CALL_TIMEOUT_SEC, TimeUnit.SECONDS);
            futures.put(l.songId, f);
        }

        int videoFilled = 0;
        List<Song> toSave = new ArrayList<>();

        for (Song song : batch) {
            if (!isBlank(song.getVideoId())) continue;

            CompletableFuture<String> f = futures.get(song.getId());
            if (f == null) continue;

            String videoId;
            try {
                videoId = f.join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof BusinessException be && be.errorCode() == ErrorCode.YOUTUBE_API_KEY_MISSING) {
                    throw be;
                }
                continue;
            }

            if (isBlank(videoId)) continue;

            song.updateVideoId(videoId);
            song.updateThumbnailImageUrl(buildYoutubeThumbnailUrl(videoId));
            toSave.add(song);
            videoFilled++;
        }

        if (!toSave.isEmpty()) songRepository.saveAll(toSave);

        return videoFilled;
    }

    private record VideoLookup(String songId, String title, String artist) {}
    private record AudioLookup(String songId, String title, String artist) {}

    private static String shortRid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String safePrefix(String s, int n) {
        if (s == null) return "null";
        return s.length() <= n ? s : s.substring(0, n);
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


    private String buildYoutubeThumbnailUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
    }
}
