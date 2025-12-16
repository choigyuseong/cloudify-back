package org.example.apispring.song.application;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.song.application.GeniusAlbumImageUrlSearchService.GeniusAlbumImageSearchResult;
import org.example.apispring.song.application.dto.YoutubeAudioFillResultDto;
import org.example.apispring.song.application.dto.YoutubeVideoThumbFillResultDto;
import org.example.apispring.song.domain.Song;
import org.example.apispring.song.domain.SongRepository;
import org.example.apispring.song.web.GeniusClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FillDbService {

    private static final int GENIUS_BATCH_SIZE = 100;

    private static final int YOUTUBE_BATCH_SIZE = 10;
    private static final int YOUTUBE_CONCURRENCY = 4;
    private static final int YOUTUBE_CALL_TIMEOUT_SEC = 9;

    private final SongRepository songRepository;

    private final GeniusClient geniusClient;
    private final GeniusAlbumImageUrlSearchService geniusAlbumImageUrlSearchService;

    private final YoutubeVideoIdSearchService youtubeVideoIdSearchService;
    private final YoutubeAudioIdSearchService youtubeAudioIdSearchService;

    private final ExecutorService youtubeExecutor = Executors.newFixedThreadPool(YOUTUBE_CONCURRENCY);

    @PreDestroy
    public void shutdown() {
        youtubeExecutor.shutdown();
    }

    // =========================================================
    // 1) GENIUS - Album Image Fill
    //   - trash 저장: "정상 응답 + 이미지 없음"일 때만
    //   - 그 외: 전부 예외
    // =========================================================
    public void fillAlbumImagesFromGenius() {
        final String rid = shortRid();
        log.info("[GeniusFill:{}] start batchSize={}", rid, GENIUS_BATCH_SIZE);

        while (true) {
            List<Song> batch = songRepository.findSongsWithoutAlbumImage(PageRequest.of(0, GENIUS_BATCH_SIZE));
            if (batch.isEmpty()) return;

            for (Song song : batch) {
                final String songId = song.getId();

                String artist = nullToEmpty(song.getArtist()).trim();
                String title  = nullToEmpty(song.getTitle()).trim();

                if (artist.isBlank() || title.isBlank()) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "songId=" + songId + " blank_metadata artist='" + artist + "' title='" + title + "'"
                    );
                }

                String query = (artist + " " + title).trim();

                // 1) 호출 실패/비정상 status면 GeniusClient에서 ErrorCode로 예외 발생
                ResponseEntity<String> res = geniusClient.search(query);

                // 2) 파싱/매칭/이미지판단은 서비스에서 수행(정책대로 trash vs 예외 결정)
                GeniusAlbumImageSearchResult r =
                        geniusAlbumImageUrlSearchService.extractAlbumImageUrl(res, title, artist, rid, songId);

                if (r.found()) {
                    song.updateAlbumImageUrl(r.url());
                } else {
                    song.updateAlbumImageUrl("trash");
                }

                // 예외가 나면 여기까지 못 오고 종료되므로, 성공한 건 즉시 저장해 누적 진행을 보장
                songRepository.save(song);
            }
        }
    }

    // =========================================================
    // 2) YOUTUBE - 기존 그대로
    // =========================================================
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

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String buildYoutubeThumbnailUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
    }
}
