package org.example.apispring.song.application;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
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

    private static final int GENIUS_BATCH_SIZE = 200;
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

    @Transactional
    public void fillAlbumImagesFromGenius() {
        while (true) {
            List<Song> batch = songRepository.findSongsWithoutAlbumImage(
                    PageRequest.of(0, GENIUS_BATCH_SIZE)
            );
            if (batch.isEmpty()) {
                break;
            }

            for (Song song : batch) {
                try {
                    String query = (song.getArtist() + " " + song.getTitle()).trim();
                    if (query.isEmpty()) {
                        song.updateAlbumImageUrl("trash");
                        songRepository.save(song);
                        continue;
                    }

                    ResponseEntity<String> res = geniusClient.search(query);
                    if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                        continue;
                    }

                    JSONObject root = new JSONObject(res.getBody());
                    JSONObject resp = root.optJSONObject("response");
                    if (resp == null) {
                        song.updateAlbumImageUrl("trash");
                        songRepository.save(song);
                        continue;
                    }

                    JSONArray hits = resp.optJSONArray("hits");
                    if (hits == null || hits.isEmpty()) {
                        song.updateAlbumImageUrl("trash");
                        songRepository.save(song);
                        continue;
                    }

                    String artUrl = selectAlbumImageUrl(hits);
                    if (artUrl == null || artUrl.isBlank()) {
                        song.updateAlbumImageUrl("trash");
                    } else {
                        song.updateAlbumImageUrl(artUrl);
                    }
                    songRepository.save(song);

                } catch (BusinessException be) {
                    if (be.errorCode() == ErrorCode.GENIUS_API_TOKEN_MISSING) {
                        throw be;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    public YoutubeVideoThumbFillResultDto fillYoutubeVideoIdAndThumbnail() {
        int thumbFilled = fillThumbnailOnlyBatch();
        int videoFilled = fillVideoIdAndThumbnailBatch();
        return new YoutubeVideoThumbFillResultDto(videoFilled, thumbFilled);
    }

    public YoutubeAudioFillResultDto fillYoutubeAudioId() {
        List<Song> batch = songRepository.findSongsWithMissingAudioId(
                PageRequest.of(0, YOUTUBE_BATCH_SIZE)
        );
        if (batch.isEmpty()) {
            return new YoutubeAudioFillResultDto(0);
        }

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
            if (!isBlank(song.getAudioId())) {
                continue;
            }

            CompletableFuture<String> f = futures.get(song.getId());
            if (f == null) {
                continue;
            }

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

            if (isBlank(audioId)) {
                continue;
            }

            song.updateAudioId(audioId);
            toSave.add(song);
            audioFilled++;
        }

        if (!toSave.isEmpty()) {
            songRepository.saveAll(toSave);
        }

        return new YoutubeAudioFillResultDto(audioFilled);
    }

    private int fillThumbnailOnlyBatch() {
        List<Song> batch = songRepository.findSongsWithMissingThumbnailOnly(
                PageRequest.of(0, YOUTUBE_BATCH_SIZE)
        );
        if (batch.isEmpty()) {
            return 0;
        }

        List<Song> toSave = new ArrayList<>();
        for (Song song : batch) {
            String videoId = song.getVideoId();
            if (isBlank(videoId)) {
                continue;
            }
            if (!isBlank(song.getThumbnailImageUrl())) {
                continue;
            }

            song.updateThumbnailImageUrl(buildYoutubeThumbnailUrl(videoId));
            toSave.add(song);
        }

        if (!toSave.isEmpty()) {
            songRepository.saveAll(toSave);
        }
        return toSave.size();
    }

    private int fillVideoIdAndThumbnailBatch() {
        List<Song> batch = songRepository.findSongsWithMissingVideoId(
                PageRequest.of(0, YOUTUBE_BATCH_SIZE)
        );
        if (batch.isEmpty()) {
            return 0;
        }

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
            if (!isBlank(song.getVideoId())) {
                continue;
            }

            CompletableFuture<String> f = futures.get(song.getId());
            if (f == null) {
                continue;
            }

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

            if (isBlank(videoId)) {
                continue;
            }

            song.updateVideoId(videoId);
            song.updateThumbnailImageUrl(buildYoutubeThumbnailUrl(videoId));
            toSave.add(song);
            videoFilled++;
        }

        if (!toSave.isEmpty()) {
            songRepository.saveAll(toSave);
        }
        return videoFilled;
    }

    private record VideoLookup(String songId, String title, String artist) {}
    private record AudioLookup(String songId, String title, String artist) {}

    private String selectAlbumImageUrl(JSONArray hits) {
        JSONObject best = null;
        double bestScore = -1.0;

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            JSONObject result = hit.optJSONObject("result");
            if (result == null) continue;

            String art = pickArtUrl(result);
            if (art == null) continue;

            String primaryArtist = norm(primaryArtistName(result));
            String rTitle = norm(result.optString("title", ""));
            String fullTitle = norm(result.optString("full_title", ""));

            double s = 0.0;

            if (!primaryArtist.isEmpty()) {
                s += 0.6;
            }

            if (!rTitle.isEmpty()) {
                s += 0.3;
            } else if (!fullTitle.isEmpty()) {
                s += 0.2;
            }

            String noisy = rTitle + " " + fullTitle;
            if (noisy.matches(".*\\b(live|cover|remix|nightcore|sped up|lyrics)\\b.*")) {
                s -= 0.25;
            }

            String uploader = norm(result.optJSONObject("primary_artist").optString("name", ""));
            if (uploader.contains("genius")) {
                s += 0.15;
            }

            if (s > bestScore) {
                bestScore = s;
                best = result;
            }
        }

        if (best != null) {
            String art = pickArtUrl(best);
            if (art != null && !art.isBlank()) {
                return art;
            }
        }

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            JSONObject result = hit.optJSONObject("result");
            String art = pickArtUrl(result);
            if (art != null && !art.isBlank()) {
                return art;
            }
        }

        return null;
    }

    private String pickArtUrl(JSONObject result) {
        if (result == null) return null;
        String[] keys = new String[]{"song_art_image_url", "song_art_image_thumbnail_url", "header_image_url"};
        for (String k : keys) {
            String v = result.optString(k, null);
            if (isHttp(v)) return v;
        }
        return null;
    }

    private String primaryArtistName(JSONObject result) {
        try {
            JSONObject pa = result.optJSONObject("primary_artist");
            if (pa != null) return pa.optString("name", "");
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean isHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
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

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String buildYoutubeThumbnailUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return null;
        }
        return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
    }
}
