package org.example.apispring.recommend.web;

import org.example.apispring.recommend.dto.CanonicalTagQuery;
import org.example.apispring.recommend.dto.CanonicalTagQuerySimple; // ✅ 새로 추가
import org.example.apispring.recommend.dto.SongResponse;
import org.example.apispring.recommend.service.RecommendationService;
import org.example.apispring.recommend.service.youtube.YouTubeService;
import org.example.apispring.youtube.web.YouTubeIdExtractor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 🎯 완성형 RecommendationController
 * - 비동기 + 캐싱 + YouTube 연동 + 썸네일 + ResponseEntity 포함
 * - 발표 / 프론트 연동 / 실제 서비스 테스트용
 */
@RestController
@RequestMapping("/api/recommend")
public class RecommendationController {

    private final RecommendationService recommender;
    private final YouTubeService yt;

    public RecommendationController(RecommendationService recommender, YouTubeService yt) {
        this.recommender = recommender;
        this.yt = yt;
    }

    @PostMapping
    public ResponseEntity<List<SongResponse>> recommend(@RequestBody CanonicalTagQuery query) {

        var list = recommender.recommend(query);

        if (list.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        // YouTube 검색 비동기 실행 + 썸네일 포함
        var futures = list.stream()
                .map(song -> CompletableFuture.supplyAsync(() -> {
                    String videoId = yt.fetchVideoIdBySearch(song.title(), song.artist());
                    return new SongResponse(
                            song.title(),
                            song.artist(),
                            videoId,
                            YouTubeService.watchUrl(videoId),
                            YouTubeService.embedUrl(videoId),
                            YouTubeService.thumbnailUrl(videoId),
                            song.albumImageUrl(),   // ✅ GeniusService 결과 포함
                            song.score()
                    );
                }))
                .toList();

        var responses = futures.stream().map(CompletableFuture::join).toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/simple")
    public ResponseEntity<List<SongResponse>> recommendSimple(@RequestBody CanonicalTagQuerySimple query) {

        var list = recommender.recommend(query);

        if (list.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        // 🎵 YouTube ID 매칭 + 썸네일 추가 (동기 방식)
        var responses = list.stream()
                .map(song -> {
                    String videoId = yt.fetchVideoIdBySearch(song.title(), song.artist());
                    return new SongResponse(
                            song.title(),
                            song.artist(),
                            videoId,
                            YouTubeService.watchUrl(videoId),
                            YouTubeService.embedUrl(videoId),
                            YouTubeService.thumbnailUrl(videoId),
                            song.albumImageUrl(),
                            song.score()
                    );
                })
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/video-id/from-url")
    public ResponseEntity<VideoIdResponse> extractFromUrl(@RequestParam String url) {
        String id = YouTubeIdExtractor.extract(url);
        return ResponseEntity.ok(new VideoIdResponse(id));
    }

    /**
     * 🔍 GET /api/recommend/video-id/by-search
     * 제목+가수 기반 YouTube 검색 → videoId 반환
     * 예시: /api/recommend/video-id/by-search?title=Love+Poem&artist=IU
     */
    @GetMapping("/video-id/by-search")
    public ResponseEntity<VideoIdResponse> bySearch(@RequestParam String title, @RequestParam String artist) {
        String id = yt.fetchVideoIdBySearch(title, artist);
        return ResponseEntity.ok(new VideoIdResponse(id));
    }

    /**
     * 🧾 GET /api/recommend/demo
     * 샘플 요청용 (테스트 및 프론트 연동 확인용)
     */
    @GetMapping("/demo")
    public ResponseEntity<List<SongResponse>> demo() {
        CanonicalTagQuery query = new CanonicalTagQuery(List.of(
                new CanonicalTagQuery.Tag("MOOD.comfort"),
                new CanonicalTagQuery.Tag("GENRE.city_pop"),
                new CanonicalTagQuery.Tag("ACTIVITY.unwind"),
                new CanonicalTagQuery.Tag("BRANCH.calm"),
                new CanonicalTagQuery.Tag("TEMPO.slow")
        ));
        return ResponseEntity.ok(recommender.recommend(query));
    }

    /**
     * ✅ 내부 응답 DTO (record 형태)
     * - 단일 videoId만 반환할 때 사용
     */
    public record VideoIdResponse(String videoId) {}
}
