package org.example.apispring.recommend.service;

import lombok.RequiredArgsConstructor;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.recommend.application.dto.LlmTagResponseDto;
import org.example.apispring.recommend.application.dto.SongResponseDto;
import org.example.apispring.recommend.domain.Song;
import org.example.apispring.recommend.domain.SongTag;
import org.example.apispring.recommend.domain.SongTagRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private final SongTagRepository songTagRepository;

    private static final int TARGET_CANDIDATES = 50;
    private static final int FINAL_RESULT_LIMIT = 30;

    private static final int STRONG_MIN_OPTIONAL_MATCH = 2;
    private static final int WEAK_MIN_OPTIONAL_MATCH = 1;

    private static final int STRONG_DB_FETCH_LIMIT = 500;
    private static final int WEAK_DB_FETCH_LIMIT = 1000;

    private static final int STABLE_TOP_K = 10;

    private static final Map<String, Double> WEIGHTS = Map.of(
            "MOOD", 0.4,
            "GENRE", 0.3,
            "ACTIVITY", 0.15,
            "BRANCH", 0.1,
            "TEMPO", 0.05
    );

    private final Random random = new Random();

    public List<SongResponseDto> recommend(LlmTagResponseDto tags) {

        try {
            return doRecommend(tags);
        } catch (BusinessException | DataAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_INTERNAL_ERROR, "Unexpected error during recommendation: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private List<SongResponseDto> doRecommend(LlmTagResponseDto tags) {
        if (tags == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "LlmTagResponseDto must not be null");
        }

        List<SongTag> strongTags = songTagRepository.findByMoodBranchAndOptionalMatchesAtLeast(
                tags.mood(),
                tags.genre(),
                tags.activity(),
                tags.branch(),
                tags.tempo(),
                STRONG_MIN_OPTIONAL_MATCH,
                PageRequest.of(0, STRONG_DB_FETCH_LIMIT)
        );

        List<SongTag> candidateTags;

        if (strongTags.size() >= TARGET_CANDIDATES) {
            candidateTags = pickRandomSubset(strongTags, TARGET_CANDIDATES);
        } else {
            candidateTags = new ArrayList<>(strongTags);

            int remaining = TARGET_CANDIDATES - strongTags.size();
            if (remaining > 0) {
                List<SongTag> strongAndWeak = songTagRepository.findByMoodBranchAndOptionalMatchesAtLeast(
                        tags.mood(),
                        tags.genre(),
                        tags.activity(),
                        tags.branch(),
                        tags.tempo(),
                        WEAK_MIN_OPTIONAL_MATCH,
                        PageRequest.of(0, WEAK_DB_FETCH_LIMIT)
                );

                Set<String> strongSongIds = strongTags.stream()
                        .map(st -> st.getSong().getId())
                        .collect(Collectors.toSet());

                List<SongTag> weakCandidates = strongAndWeak.stream()
                        .filter(st -> !strongSongIds.contains(st.getSong().getId()))
                        .filter(st -> computeOptionalMatchCount(st, tags) == 1)
                        .toList();

                if (!weakCandidates.isEmpty()) {
                    List<SongTag> selectedWeak = pickRandomSubset(weakCandidates, remaining);
                    candidateTags.addAll(selectedWeak);
                }
            }
        }

        if (candidateTags.isEmpty()) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_NO_CANDIDATES);
        }

        List<ScoredSong> scored = candidateTags.stream()
                .map(st -> new ScoredSong(st.getSong(), computeScore(st, tags)))
                .toList();

        scored.sort(Comparator.comparingDouble(ScoredSong::score).reversed());

        if (scored.size() > STABLE_TOP_K) {
            List<ScoredSong> head = new ArrayList<>(scored.subList(0, STABLE_TOP_K));
            List<ScoredSong> tail = new ArrayList<>(scored.subList(STABLE_TOP_K, scored.size()));
            Collections.shuffle(tail, random);

            scored = new ArrayList<>(head.size() + tail.size());
            scored.addAll(head);
            scored.addAll(tail);
        }

        return scored.stream()
                .limit(FINAL_RESULT_LIMIT)
                .map(sc -> SongResponseDto.of(sc.song()))
                .toList();
    }

    private int computeOptionalMatchCount(SongTag st, LlmTagResponseDto tags) {
        int count = 0;
        if (equalsIgnoreCase(tags.genre(), st.getGenre())) count++;
        if (equalsIgnoreCase(tags.activity(), st.getActivity())) count++;
        if (equalsIgnoreCase(tags.tempo(), st.getTempo())) count++;
        return count;
    }

    private double computeScore(SongTag st, LlmTagResponseDto tags) {
        double score = 0.0;

        if (equalsIgnoreCase(tags.mood(), st.getMood())) score += WEIGHTS.get("MOOD");
        if (equalsIgnoreCase(tags.genre(), st.getGenre())) score += WEIGHTS.get("GENRE");
        if (equalsIgnoreCase(tags.activity(), st.getActivity())) score += WEIGHTS.get("ACTIVITY");
        if (equalsIgnoreCase(tags.branch(), st.getBranch())) score += WEIGHTS.get("BRANCH");
        if (equalsIgnoreCase(tags.tempo(), st.getTempo())) score += WEIGHTS.get("TEMPO");

        return score;
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private List<SongTag> pickRandomSubset(List<SongTag> source, int limit) {
        if (source.size() <= limit) {
            return new ArrayList<>(source);
        }
        List<SongTag> copy = new ArrayList<>(source);
        Collections.shuffle(copy, random);
        return copy.subList(0, limit);
    }

    private record ScoredSong(Song song, double score) {
    }
}
