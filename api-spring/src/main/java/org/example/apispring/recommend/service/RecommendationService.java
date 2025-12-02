package org.example.apispring.recommend.service;

import lombok.RequiredArgsConstructor;
import org.example.apispring.recommend.domain.SongRecord;
import org.example.apispring.recommend.domain.SongRecordRepository;
import org.example.apispring.recommend.dto.CanonicalTagQuery;
import org.example.apispring.recommend.dto.CanonicalTagQuerySimple;
import org.example.apispring.recommend.dto.SongResponse;
import org.example.apispring.recommend.service.youtube.YouTubeService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ✅ 외부 API 호출 없음: 점수 계산만 수행
 * - YouTube/Genius 호출은 컨트롤러에서 상위 N개에 한해 수행
 * - 공식 영상/공식 음원 보정 α 적용
 * - 국내 공식 채널은 contains 기반으로 유연 체크
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

    private static final double OFFICIAL_BONUS = 0.05; // α 값
    private static final Set<String> DOMESTIC_OFFICIAL_CHANNELS = Set.of(
            "WonderK", "SMTOWN", "JYP Entertainment" // 필요시 추가
    );

    private final YouTubeService youTubeService;

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

            // ───────────── 공식 영상/공식 음원 보정 ─────────────
            if (Boolean.TRUE.equals(s.getYoutubeVerified())) {
                score += OFFICIAL_BONUS; // YouTube verified
            }

            if (s.getYoutubeChannel() != null) {
                for (String ch : DOMESTIC_OFFICIAL_CHANNELS) {
                    if (s.getYoutubeChannel().toLowerCase().contains(ch.toLowerCase())) {
                        score += OFFICIAL_BONUS; // 국내 공식 채널
                        break;
                    }
                }
            }

            if (s.getGeniusUploader() != null && s.getGeniusUploader().equalsIgnoreCase("Genius")) {
                score += OFFICIAL_BONUS; // Genius 공식 음원
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

            // ───────────── 공식 영상/공식 음원 보정 ─────────────
            if (Boolean.TRUE.equals(s.getYoutubeVerified())) {
                score += OFFICIAL_BONUS; // YouTube verified
            }

            if (s.getYoutubeChannel() != null) {
                for (String ch : DOMESTIC_OFFICIAL_CHANNELS) {
                    if (s.getYoutubeChannel().toLowerCase().contains(ch.toLowerCase())) {
                        score += OFFICIAL_BONUS; // 국내 공식 채널
                        break;
                    }
                }
            }

            if (s.getGeniusUploader() != null && s.getGeniusUploader().equalsIgnoreCase("Genius")) {
                score += OFFICIAL_BONUS; // Genius 공식 음원
            }

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
