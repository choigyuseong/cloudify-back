package org.example.apispring.reco.web;

import org.example.apispring.reco.service.youtube.YouTubeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 🎵 MusicController
 * - YouTubeService를 직접 호출하여 음악 검색/상세 조회 제공
 * - /api/music/** 엔드포인트 담당
 * - Cloudify 추천 결과 외부 검증용 (단독 테스트/프론트 미리보기용)
 */
@RestController
@RequestMapping("/api/music")
@CrossOrigin(origins = "*")
public class MusicController {

    private final YouTubeService yt;

    public MusicController(YouTubeService yt) {
        this.yt = yt;
    }

    /**
     * 🔍 GET /api/music/search
     * 제목 + 아티스트로 YouTube 검색 후 videoId 반환
     * 예시: /api/music/search?title=Love+Poem&artist=IU
     */
    @GetMapping("/search")
    public ResponseEntity<MusicSearchResponse> search(
            @RequestParam String title,
            @RequestParam String artist
    ) {
        String videoId = yt.fetchVideoIdBySearch(title, artist);
        if (videoId == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(new MusicSearchResponse(
                title,
                artist,
                videoId,
                YouTubeService.watchUrl(videoId),
                YouTubeService.embedUrl(videoId),
                YouTubeService.thumbnailUrl(videoId)
        ));
    }

    /**
     * 🎬 GET /api/music/search/async
     * 비동기 버전 — @Async 기반 (fetchVideoIdAsync 사용)
     */
    @GetMapping("/search/async")
    public CompletableFuture<ResponseEntity<MusicSearchResponse>> searchAsync(
            @RequestParam String title,
            @RequestParam String artist
    ) {
        return yt.fetchVideoIdAsync(title, artist)
                .thenApply(videoId -> {
                    if (videoId == null)
                        return ResponseEntity.notFound().build();

                    return ResponseEntity.ok(new MusicSearchResponse(
                            title,
                            artist,
                            videoId,
                            YouTubeService.watchUrl(videoId),
                            YouTubeService.embedUrl(videoId),
                            YouTubeService.thumbnailUrl(videoId)
                    ));
                });
    }

    /**
     * 🧾 GET /api/music/demo
     * 테스트용 — Cloudify 프론트에서 단독 YouTube 연결 테스트 시 사용
     */
    @GetMapping("/demo")
    public ResponseEntity<List<MusicSearchResponse>> demo() {
        var examples = List.of(
                new String[]{"Plastic Love", "Mariya Takeuchi"},
                new String[]{"Stay With Me", "Miki Matsubara"},
                new String[]{"Sparkle", "Tatsuro Yamashita"}
        );

        var results = examples.stream()
                .map(arr -> {
                    String title = arr[0];
                    String artist = arr[1];
                    String videoId = yt.fetchVideoIdBySearch(title, artist);
                    return new MusicSearchResponse(
                            title,
                            artist,
                            videoId,
                            YouTubeService.watchUrl(videoId),
                            YouTubeService.embedUrl(videoId),
                            YouTubeService.thumbnailUrl(videoId)
                    );
                })
                .toList();

        return ResponseEntity.ok(results);
    }

    /**
     * ✅ 내부 응답 DTO (record 형태)
     * - 프론트에서 바로 렌더링 가능한 구조
     */
    public record MusicSearchResponse(
            String title,
            String artist,
            String videoId,
            String watchUrl,
            String embedUrl,
            String thumbnailUrl
    ) {}
}