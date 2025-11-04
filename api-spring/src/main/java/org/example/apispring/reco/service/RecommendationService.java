package org.example.apispring.reco.service;

import org.example.apispring.reco.domain.SongRecord;
import org.example.apispring.reco.dto.CanonicalTagQuery;
import org.example.apispring.reco.dto.SongResponse;
import org.example.apispring.reco.service.youtube.YouTubeService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class RecommendationService {

    private final CsvLoader csvLoader;
    private final YouTubeService youtubeService;
    private final GeniusService geniusService; // ✅ Genius API 서비스 추가

    // 🎯 태그별 가중치 (정책 기반)
    private static final Map<String, Double> WEIGHTS = Map.of(
            "MOOD", 0.4,
            "GENRE", 0.3,
            "ACTIVITY", 0.15,
            "BRANCH", 0.1,
            "TEMPO", 0.05
    );

    public RecommendationService(CsvLoader csvLoader, YouTubeService youtubeService, GeniusService geniusService) {
        this.csvLoader = csvLoader;
        this.youtubeService = youtubeService;
        this.geniusService = geniusService;
    }

    /**
     * 🎯 CanonicalTagQuery 기반 추천 (Top-30)
     * - CSV 로드 후 곡별 유사도 계산
     * - YouTube videoId 및 Genius 앨범 이미지 비동기 조회
     */
    public List<SongResponse> recommend(CanonicalTagQuery query) {
        List<SongRecord> songs = csvLoader.getSongs();
        if (songs == null || songs.isEmpty()) return List.of();

        Map<SongRecord, Double> scored = new HashMap<>();

        // ✅ 태그 기반 점수 계산
        for (SongRecord song : songs) {
            double score = 0.0;
            if (query.getTags() == null) continue;

            for (CanonicalTagQuery.Tag tag : query.getTags()) {
                String id = tag.id();
                String type = id.split("\\.")[0].toUpperCase();
                double weight = WEIGHTS.getOrDefault(type, 0.0);

                if (song.constraints() != null && song.constraints().matches(id)) {
                    score += weight;
                }
            }
            scored.put(song, score);
        }

        // ✅ 상위 30곡 정렬
        List<Map.Entry<SongRecord, Double>> top30 = scored.entrySet().stream()
                .sorted(Map.Entry.<SongRecord, Double>comparingByValue().reversed())
                .limit(30)
                .toList();

        // ✅ 비동기 YouTube + Genius API 조회
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<CompletableFuture<SongResponse>> futures = top30.stream()
                .map(entry -> CompletableFuture.supplyAsync(() -> {
                    SongRecord s = entry.getKey();
                    double score = entry.getValue();

                    // 🎥 YouTube 정보
                    String videoId = youtubeService.fetchVideoIdBySearch(s.title(), s.artist());

                    // 🎨 Genius 앨범 이미지
                    String albumImageUrl = geniusService.fetchAlbumImage(s.title(), s.artist());

                    // 🎧 SongResponse 생성 (record 기반)
                    return new SongResponse(
                            s.title(),
                            s.artist(),
                            videoId,
                            YouTubeService.watchUrl(videoId),
                            YouTubeService.embedUrl(videoId),
                            YouTubeService.thumbnailUrl(videoId),
                            albumImageUrl,  // ✅ 추가
                            score
                    );
                }, executor))
                .toList();

        List<SongResponse> responses = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        executor.shutdown();
        return responses;
    }
}
