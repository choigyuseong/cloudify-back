package org.example.apispring.recommend.web;

import org.example.apispring.recommend.dto.CanonicalTagQuery;
import org.example.apispring.recommend.dto.CanonicalTagQuerySimple;
import org.example.apispring.recommend.dto.SongResponse;
import org.example.apispring.recommend.service.RecommendationService;
import org.example.apispring.recommend.service.GeniusService;
import org.example.apispring.recommend.service.youtube.YouTubeService;
import org.example.apispring.recommend.service.youtube.YouTubeServiceLyrics; // ⭐ 추가
import org.example.apispring.youtube.web.YouTubeIdExtractor;
import org.example.apispring.recommend.service.VideoIdFillService;   // ⭐ 추가
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/recommend")
@CrossOrigin(origins = "*")
public class RecommendationController {

    private final RecommendationService recommender;
    private final YouTubeService yt;
    private final YouTubeServiceLyrics ytLyrics;                 // ⭐ 추가
    private final GeniusService genius;
    private final VideoIdFillService videoIdFillService;         // ⭐ 추가

    public RecommendationController(
            RecommendationService recommender,
            YouTubeService yt,
            YouTubeServiceLyrics ytLyrics,                       // ⭐ 생성자에 주입
            GeniusService genius,
            VideoIdFillService videoIdFillService                // ⭐ 생성자에 주입
    ) {
        this.recommender = recommender;
        this.yt = yt;
        this.ytLyrics = ytLyrics;                                // ⭐ 저장
        this.genius = genius;
        this.videoIdFillService = videoIdFillService;           // ⭐ 저장
    }

    /**
     * 🎯 POST /api/recommend
     * - CanonicalTagQuery 기반 추천
     * - limit 미지정 시 기본 5개만 반환(정확도 점검/쿼터 절약)
     * - YouTube/Genius 보강도 상위 N개에만 수행
     */
    @PostMapping
    public ResponseEntity<List<SongResponse>> recommend(
            @RequestBody CanonicalTagQuery query,
            @RequestParam(name = "limit", required = false, defaultValue = "5") int limit
    ) {
        var list = recommender.recommend(query);
        if (list.isEmpty()) return ResponseEntity.noContent().build();

        int n = Math.max(1, limit);
        var top = list.stream().limit(n).toList();

        var futures = top.stream()
                .map(song -> CompletableFuture.supplyAsync(() -> {

                    String videoId = song.videoId();
                    if (videoId == null || videoId.isBlank()) {
                        videoId = yt.fetchVideoIdBySearch(song.title(), song.artist());
                    }

                    String watch = (videoId == null) ? null : YouTubeService.watchUrl(videoId);
                    String embed = (videoId == null) ? null : YouTubeService.embedUrl(videoId);
                    String thumb = (videoId == null) ? null : YouTubeService.thumbnailUrl(videoId);

                    String album = genius.fetchAlbumImage(song.title(), song.artist());

                    return new SongResponse(
                            song.title(),
                            song.artist(),
                            videoId,
                            watch,
                            embed,
                            thumb,
                            album,
                            song.score()
                    );
                }))
                .toList();

        var responses = futures.stream().map(CompletableFuture::join).toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * 🟣 POST /api/recommend/simple
     * - CanonicalTagQuerySimple 기반 추천
     * - limit 미지정 시 기본 5개
     */
    @PostMapping("/simple")
    public ResponseEntity<List<SongResponse>> recommendSimple(
            @RequestBody CanonicalTagQuerySimple query,
            @RequestParam(name = "limit", required = false, defaultValue = "5") int limit
    ) {
        var list = recommender.recommend(query);
        if (list.isEmpty()) return ResponseEntity.noContent().build();

        int n = Math.max(1, limit);
        var top = list.stream().limit(n).toList();

        var responses = top.stream()
                .map(song -> {

                    String videoId = song.videoId();
                    if (videoId == null || videoId.isBlank()) {
                        videoId = yt.fetchVideoIdBySearch(song.title(), song.artist());
                    }

                    String watch = (videoId == null) ? null : YouTubeService.watchUrl(videoId);
                    String embed = (videoId == null) ? null : YouTubeService.embedUrl(videoId);
                    String thumb = (videoId == null) ? null : YouTubeService.thumbnailUrl(videoId);
                    String album = genius.fetchAlbumImage(song.title(), song.artist());

                    return new SongResponse(
                            song.title(),
                            song.artist(),
                            videoId,
                            watch,
                            embed,
                            thumb,
                            album,
                            song.score()
                    );
                })
                .toList();

        return ResponseEntity.ok(responses);
    }

    /** 🎬 GET /api/recommend/video-id/from-url?url=... */
    @GetMapping("/video-id/from-url")
    public ResponseEntity<VideoIdResponse> extractFromUrl(@RequestParam String url) {
        String id = YouTubeIdExtractor.extract(url);
        return ResponseEntity.ok(new VideoIdResponse(id));
    }

    /** 🔍 GET /api/recommend/video-id/by-search?title=...&artist=... */
    @GetMapping("/video-id/by-search")
    public ResponseEntity<VideoIdResponse> bySearch(@RequestParam String title, @RequestParam String artist) {
        String id = yt.fetchVideoIdBySearch(title, artist);
        return ResponseEntity.ok(new VideoIdResponse(id));
    }

    /** 🎧 GET /api/recommend/audio-id/by-search?title=...&artist=... */
    @GetMapping("/audio-id/by-search")
    public ResponseEntity<AudioIdResponse> bySearchLyrics(@RequestParam String title, @RequestParam String artist) {
        String id = ytLyrics.fetchAudioIdBySearch(title, artist);
        return ResponseEntity.ok(new AudioIdResponse(id));
    }

    /** 🧾 샘플 */
    @GetMapping("/demo")
    public ResponseEntity<List<SongResponse>> demo() {
        CanonicalTagQuery query = new CanonicalTagQuery(List.of(
                new CanonicalTagQuery.Tag("MOOD.comfort".toLowerCase(Locale.ROOT)),
                new CanonicalTagQuery.Tag("GENRE.city_pop".toLowerCase(Locale.ROOT)),
                new CanonicalTagQuery.Tag("ACTIVITY.unwind".toLowerCase(Locale.ROOT)),
                new CanonicalTagQuery.Tag("BRANCH.calm".toLowerCase(Locale.ROOT)),
                new CanonicalTagQuery.Tag("TEMPO.slow".toLowerCase(Locale.ROOT))
        ));
        return ResponseEntity.ok(recommender.recommend(query));
    }

    /** 🧹 DB videoId 자동 갱신 */
    @PostMapping("/fill-video-id")   // ⭐ 추가
    public ResponseEntity<String> fillVideoIds() {
        videoIdFillService.fillVideoIds();  // ⭐ batch DB update
        return ResponseEntity.ok("DONE");
    }

    /** 단일 응답 DTO */
    public record VideoIdResponse(String videoId) {}

    /** 단일 응답 DTO (lyrics/audio) */
    public record AudioIdResponse(String audioId) {}
}
