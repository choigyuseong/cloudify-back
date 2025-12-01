package org.example.apispring.recommend.web;

<<<<<<< HEAD:api-spring/src/main/java/org/example/apispring/reco/web/MusicController.java
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.reco.dto.CanonicalTagQuery;
import org.example.apispring.reco.dto.SongResponse;
import org.example.apispring.reco.service.RecommendationService;
import org.example.apispring.reco.service.parser.ConstraintParserService;
import org.example.apispring.reco.service.GeniusService;
import org.example.apispring.reco.service.youtube.YouTubeService;
import org.example.apispring.youtube.web.YouTubeIdExtractor;
=======
import org.example.apispring.recommend.service.youtube.YouTubeService;
>>>>>>> c0e0d7b8e38009d428738b37315c9116f19884b6:api-spring/src/main/java/org/example/apispring/recommend/web/MusicController.java
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

<<<<<<< HEAD:api-spring/src/main/java/org/example/apispring/reco/web/MusicController.java
/**
 * 🎵 음악 추천 API (LLM 기반)
 * - POST /api/music/recommend-by-text: 자연어 입력 → LLM 파싱 → 추천
 * - GET /api/music/search: 제목+아티스트 검색
 */
@Slf4j
=======
>>>>>>> c0e0d7b8e38009d428738b37315c9116f19884b6:api-spring/src/main/java/org/example/apispring/recommend/web/MusicController.java
@RestController
@RequestMapping("/api/music")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MusicController {

    private final ConstraintParserService llmParser;  // Gemini 파서
    private final RecommendationService recommender;
    private final YouTubeService yt;
    private final GeniusService genius;

    /**
     * 🎯 POST /api/music/recommend-by-text
     *
     * **사용자 자연어 입력 → LLM 파싱 → 추천**
     *
     * 요청 예시:
     * {
     *   "text": "나 오늘 우울한데 빵사먹었어. 이런 나를 위로해주는 노래를 추천해줘.",
     *   "locale": "ko-KR",
     *   "limit": 5
     * }
     *
     * 응답: 추천곡 리스트 (YouTube/Genius 링크 포함)
     */
    @PostMapping("/recommend-by-text")
    public ResponseEntity<MusicSearchResponse> recommendByText(@RequestBody MusicSearchRequest request) {
        try {
            log.info("📥 자연어 추천 요청: text='{}', limit={}", request.text, request.limit);

            // 1️⃣ LLM 파싱 (자연어 → 카논 태그)
            CanonicalTagQuery query = llmParser.parseToCanonicalTags(
                    request.text,
                    request.locale != null ? request.locale : "ko-KR"
            );
            log.info("📌 LLM 파싱 결과 tags size={}", query.getTags().size());
            for (CanonicalTagQuery.Tag t : query.getTags()) {
                log.info(" - tag={}", t.id());
            }


            // 2️⃣ 추천 (점수 계산)
            var recommendations = recommender.recommend(query);
            if (recommendations.isEmpty()) {
                return ResponseEntity.noContent().build();
            }

            // 3️⃣ 상위 N개만 YouTube/Genius 조회
            int limit = Math.max(1, request.limit != null ? request.limit : 5);
            var top = recommendations.stream().limit(limit).toList();

            var futures = top.stream()
                    .map(song -> CompletableFuture.supplyAsync(() -> {
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
                    }))
                    .toList();

            var responses = futures.stream().map(CompletableFuture::join).toList();

            log.info("✅ 추천 완료: {}곡 반환", responses.size());
            return ResponseEntity.ok(new MusicSearchResponse(
                    query,
                    responses
            ));

        } catch (Exception e) {
            log.error("❌ 추천 실패", e);
            return ResponseEntity.internalServerError().build();
        }
    }

<<<<<<< HEAD:api-spring/src/main/java/org/example/apispring/reco/web/MusicController.java
    /**
     * 🔍 GET /api/music/search
     *
     * **제목+아티스트로 직접 검색**
     *
     * 쿼리 파라미터:
     * - title: 곡 제목
     * - artist: 아티스트명
     *
     * 응답: YouTube 링크 정보
     */
=======
>>>>>>> c0e0d7b8e38009d428738b37315c9116f19884b6:api-spring/src/main/java/org/example/apispring/recommend/web/MusicController.java
    @GetMapping("/search")
    public ResponseEntity<VideoIdResponse> search(
            @RequestParam String title,
            @RequestParam String artist
    ) {
        try {
            log.info("🔍 곡 검색: title='{}', artist='{}'", title, artist);

            String videoId = yt.fetchVideoIdBySearch(title, artist);
            if (videoId == null) {
                return ResponseEntity.notFound().build();
            }

            String watch = YouTubeService.watchUrl(videoId);
            String embed = YouTubeService.embedUrl(videoId);
            String thumb = YouTubeService.thumbnailUrl(videoId);
            String album = genius.fetchAlbumImage(title, artist);

            return ResponseEntity.ok(new VideoIdResponse(
                    videoId,
                    watch,
                    embed,
                    thumb,
                    album
            ));

        } catch (Exception e) {
            log.error("❌ 검색 실패", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ───────────────────────────────────────────────────────────
    // DTO
    // ───────────────────────────────────────────────────────────

    public record MusicSearchRequest(
            String text,
            String locale,
            Integer limit
    ) {}

    public record MusicSearchResponse(
            CanonicalTagQuery parsedQuery,
            List<SongResponse> recommendations
    ) {}

    public record VideoIdResponse(
            String videoId,
            String watchUrl,
            String embedUrl,
            String thumbnailUrl,
            String albumImageUrl
    ) {}
}