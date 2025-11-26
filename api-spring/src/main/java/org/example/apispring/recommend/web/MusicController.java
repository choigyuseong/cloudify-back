package org.example.apispring.recommend.web;

import org.example.apispring.recommend.service.youtube.YouTubeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/music")
@CrossOrigin(origins = "*")
public class MusicController {

    private final YouTubeService yt;

    public MusicController(YouTubeService yt) {
        this.yt = yt;
    }

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
