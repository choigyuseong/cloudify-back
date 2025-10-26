package org.example.apispring.reco.service;

import org.example.apispring.reco.dto.*;
import org.example.apispring.youtube.web.YouTubeService;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class RecommendationService {

    private final CsvLoader csvLoader;
    private final YouTubeService youtubeService;

    private static final Map<String, Double> WEIGHTS = Map.of(
            "MOOD", 0.4,
            "GENRE", 0.3,
            "ACTIVITY", 0.15,
            "BRANCH", 0.1,
            "TEMPO", 0.05
    );

    public RecommendationService(CsvLoader csvLoader, YouTubeService youtubeService) {
        this.csvLoader = csvLoader;
        this.youtubeService = youtubeService;
    }

    public List<SongResponse> recommend(CanonicalTagQuery query) {
        List<SongRecord> songs = csvLoader.getSongs();
        if (songs.isEmpty()) return List.of();

        Map<SongRecord, Double> scored = new HashMap<>();

        for (SongRecord song : songs) {
            double score = 0.0;

            for (var tag : query.tags) {
                String id = tag.id();
                String type = id.split("\\.")[0].toUpperCase();

                double weight = WEIGHTS.getOrDefault(type, 0.0);
                if (song.constraints().matches(id)) {
                    score += weight;
                }
            }
            scored.put(song, score);
        }

        // 상위 30곡 선택
        return scored.entrySet().stream()
                .sorted(Map.Entry.<SongRecord, Double>comparingByValue().reversed())
                .limit(30)
                .map(e -> {
                    SongRecord s = e.getKey();
                    double score = e.getValue();

                    // YouTube ID 비동기로 조회
                    String videoId = youtubeService.fetchVideoIdBySearch(s.title(), s.artist());

                    return new SongResponse(
                            s.title(),
                            s.artist(),
                            videoId,
                            YouTubeService.watchUrl(videoId),
                            YouTubeService.embedUrl(videoId),
                            YouTubeService.thumbnailUrl(videoId),
                            score
                    );
                })
                .collect(Collectors.toList());
    }
}
