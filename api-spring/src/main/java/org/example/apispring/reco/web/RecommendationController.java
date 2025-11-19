package org.example.apispring.reco.web;

import org.example.apispring.reco.dto.CanonicalTagQuery;
import org.example.apispring.reco.dto.CanonicalTagQuerySimple;
import org.example.apispring.reco.dto.SongResponse;
import org.example.apispring.reco.service.RecommendationService;
import org.example.apispring.reco.service.GeniusService;
import org.example.apispring.reco.service.youtube.YouTubeService;
import org.example.apispring.youtube.web.YouTubeIdExtractor;
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
    private final GeniusService genius;

    public RecommendationController(
            RecommendationService recommender,
            YouTubeService yt,
            GeniusService genius
    ) {
        this.recommender = recommender;
        this.yt = yt;
        this.genius = genius;
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
                    // YouTube: 상위 N개만 조회
                    String videoId = yt.fetchVideoIdBySearch(song.title(), song.artist());
                    String watch = (videoId == null) ? null : YouTubeService.watchUrl(videoId);
                    String embed = (videoId == null) ? null : YouTubeService.embedUrl(videoId);
                    String thumb = (videoId == null) ? null : YouTubeService.thumbnailUrl(videoId);

                    // Genius: 상위 N개만 조회
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
     * ✅ POST /api/recommend/simple
     * - CanonicalTagQuerySimple 기반 추천
     * - limit 미지정 시 기본 5개
     * - 상위 N개만 YouTube/Genius 조회(동기)
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
                    String videoId = yt.fetchVideoIdBySearch(song.title(), song.artist());
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

    /** 단일 응답 DTO */
    public record VideoIdResponse(String videoId) {}
}
