package org.example.apispring.song.application;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.song.application.GeniusAlbumImageUrlSearchService.GeniusAlbumImageSearchResult;
import org.example.apispring.song.application.dto.GeniusAlbumImageFillResultDto;
import org.example.apispring.song.application.dto.YoutubeAudioFillResultDto;
import org.example.apispring.song.application.dto.YoutubeVideoThumbFillResultDto;
import org.example.apispring.song.domain.Song;
import org.example.apispring.song.domain.SongRepository;
import org.example.apispring.song.web.GeniusClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final GeniusAlbumImageUrlSearchService geniusAlbumImageUrlSearchService;
    private final SongQueryNormalizationService songQueryNormalizationService;

    private final YoutubeVideoIdSearchService youtubeVideoIdSearchService;
    private final YoutubeAudioIdSearchService youtubeAudioIdSearchService;

    private final ExecutorService youtubeExecutor = Executors.newFixedThreadPool(YOUTUBE_CONCURRENCY);

    @PreDestroy
    public void shutdown() {
        youtubeExecutor.shutdown();
    }

    @Transactional
    public GeniusAlbumImageFillResultDto fillAlbumImagesFromGenius(int limit) {
        int batchSize = clamp(limit, 1, GENIUS_BATCH_SIZE);
        String rid = shortRid();

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
            String songId = song.getId();

            String artist = nullToEmpty(song.getArtist()).trim();
            String title = nullToEmpty(song.getTitle()).trim();

            if ((artist + title).isBlank()) {
                song.updateAlbumImageUrl("trash");
                toSave.add(song);
                updated++;
                trash++;
                continue;
            }

            String cleanTitle = songQueryNormalizationService.cleanTitle(title);

            List<QueryAttempt> attempts = buildGeniusQueryAttempts(artist, title, cleanTitle);

            String selectedUrl = null;
            boolean sawNoImageOrNoHitsOnly = true;

            for (QueryAttempt attempt : attempts) {
                ResponseEntity<String> res;
                try {
                    res = geniusClient.search(attempt.query());
                } catch (BusinessException be) {
                    throw be;
                } catch (Exception e) {
                    throw e;
                }

                GeniusAlbumImageSearchResult r = geniusAlbumImageUrlSearchService.extractAlbumImageUrl(
                        res,
                        attempt.titleForScoring(),
                        artist,
                        rid,
                        songId
                );

                if (r.found()) {
                    selectedUrl = r.url();
                    break;
                }

                if (!"NO_HITS".equals(r.reason()) && !"NO_IMAGE_URL".equals(r.reason())) {
                    sawNoImageOrNoHitsOnly = false;
                    break;
                }
            }

            if (selectedUrl != null && !selectedUrl.isBlank()) {
                song.updateAlbumImageUrl(selectedUrl);
                toSave.add(song);
                updated++;
                success++;
                continue;
            }

            if (sawNoImageOrNoHitsOnly) {
                song.updateAlbumImageUrl("trash");
                toSave.add(song);
                updated++;
                trash++;
            } else {
                transientSkip++;
            }
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
                transientSkip
        );
    }

    private record QueryAttempt(String query, String titleForScoring) {}

    private List<QueryAttempt> buildGeniusQueryAttempts(String artist, String title, String cleanTitle) {
        LinkedHashMap<String, QueryAttempt> map = new LinkedHashMap<>();

        String a = nullToEmpty(artist).trim();
        String t = nullToEmpty(title).trim();
        String ct = nullToEmpty(cleanTitle).trim();

        putAttempt(map, join(a, t), t);
        putAttempt(map, join(t, a), t);

        if (!ct.isBlank() && !ct.equals(t)) {
            putAttempt(map, join(a, ct), ct);
            putAttempt(map, join(ct, a), ct);
        }

        return new ArrayList<>(map.values());
    }

    private void putAttempt(Map<String, QueryAttempt> map, String query, String titleForScoring) {
        if (query == null) return;
        String q = query.replaceAll("\\s+", " ").trim();
        if (q.isBlank()) return;
        map.putIfAbsent(q, new QueryAttempt(q, titleForScoring));
    }

    private String join(String left, String right) {
        return (nullToEmpty(left).trim() + " " + nullToEmpty(right).trim()).trim();
    }

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
                if (cause instanceof BusinessException be) {
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
                if (cause instanceof BusinessException be) {
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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String buildYoutubeThumbnailUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
    }
}
