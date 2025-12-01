package org.example.apispring.recommend.service;

import lombok.RequiredArgsConstructor;
<<<<<<< HEAD:api-spring/src/main/java/org/example/apispring/reco/service/RecommendationService.java
import org.example.apispring.reco.domain.SongRecord;
import org.example.apispring.reco.domain.SongRecordRepository;
import org.example.apispring.reco.dto.CanonicalTagQuery;
import org.example.apispring.reco.dto.CanonicalTagQuerySimple;
import org.example.apispring.reco.dto.SongResponse;
=======
import org.example.apispring.recommend.domain.SongRecord;
import org.example.apispring.recommend.domain.SongRecordRepository;
import org.example.apispring.recommend.dto.CanonicalTagQuery;
import org.example.apispring.recommend.dto.CanonicalTagQuerySimple;
import org.example.apispring.recommend.dto.SongResponse;
import org.example.apispring.recommend.service.youtube.YouTubeService;
>>>>>>> c0e0d7b8e38009d428738b37315c9116f19884b6:api-spring/src/main/java/org/example/apispring/recommend/service/RecommendationService.java
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ✅ 외부 API 호출 없음: 점수 계산만 수행
 * - YouTube/Genius 호출은 컨트롤러에서 상위 N개에 한해 수행
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final SongRecordRepository songRepo;

    private static final Map<String, Double> WEIGHTS = Map.of(
            "MOOD", 0.4,
            "GENRE", 0.3,
            "ACTIVITY", 0.15,
            "BRANCH", 0.1,
            "TEMPO", 0.05
    );

    // ───────────── LLM 기반 ─────────────
    public List<SongResponse> recommend(CanonicalTagQuery query) {
        List<SongRecord> allSongs = songRepo.findAll();
        Map<SongRecord, Double> scored = new HashMap<>();

        for (SongRecord s : allSongs) {
            double score = 0.0;
            for (CanonicalTagQuery.Tag tag : query.getTags()) {
                String id = tag.id().toLowerCase(Locale.ROOT);
                if (id.contains("mood") && id.contains(s.getMood().toLowerCase(Locale.ROOT)))    score += WEIGHTS.get("MOOD");
                if (id.contains("genre") && id.contains(s.getGenre().toLowerCase(Locale.ROOT)))  score += WEIGHTS.get("GENRE");
                if (id.contains("activity") && id.contains(s.getActivity().toLowerCase(Locale.ROOT))) score += WEIGHTS.get("ACTIVITY");
                if (id.contains("branch") && id.contains(s.getBranch().toLowerCase(Locale.ROOT))) score += WEIGHTS.get("BRANCH");
                if (id.contains("tempo") && id.contains(s.getTempo().toLowerCase(Locale.ROOT)))   score += WEIGHTS.get("TEMPO");
            }
            scored.put(s, score);
        }

        return scored.entrySet().stream()
                .sorted(Map.Entry.<SongRecord, Double>comparingByValue().reversed())
                .limit(30)
                .map(e -> new SongResponse(
                        e.getKey().getTitle(),
                        e.getKey().getArtist(),
                        null, null, null, null, // YouTube는 컨트롤러에서 채움
                        null,                    // Genius도 컨트롤러에서 채움
                        e.getValue()
                ))
                .collect(Collectors.toList());
    }

    // ──────────── Simple 기반 ────────────
    public List<SongResponse> recommend(CanonicalTagQuerySimple query) {
        List<SongRecord> allSongs = songRepo.findAll();
        Map<SongRecord, Double> scored = new HashMap<>();

        for (SongRecord s : allSongs) {
            double score = 0.0;
            if (query.mood() != null && query.mood().equalsIgnoreCase(s.getMood()))         score += WEIGHTS.get("MOOD");
            if (query.genre() != null && query.genre().equalsIgnoreCase(s.getGenre()))       score += WEIGHTS.get("GENRE");
            if (query.activity() != null && query.activity().equalsIgnoreCase(s.getActivity())) score += WEIGHTS.get("ACTIVITY");
            if (query.branch() != null && query.branch().equalsIgnoreCase(s.getBranch()))    score += WEIGHTS.get("BRANCH");
            if (query.tempo() != null && query.tempo().equalsIgnoreCase(s.getTempo()))       score += WEIGHTS.get("TEMPO");
            scored.put(s, score);
        }

        return scored.entrySet().stream()
                .sorted(Map.Entry.<SongRecord, Double>comparingByValue().reversed())
                .limit(30)
                .map(e -> new SongResponse(
                        e.getKey().getTitle(),
                        e.getKey().getArtist(),
                        null, null, null, null,
                        null,
                        e.getValue()
                ))
                .collect(Collectors.toList());
    }
}
