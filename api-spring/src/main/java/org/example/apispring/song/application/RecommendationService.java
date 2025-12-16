package org.example.apispring.song.application;

import lombok.RequiredArgsConstructor;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.song.application.dto.LlmTagResponseDto;
import org.example.apispring.song.application.dto.SongResponseDto;
import org.example.apispring.song.domain.Song;
import org.example.apispring.song.domain.SongTag;
import org.example.apispring.song.domain.SongTagRepository;
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

    private static final int TARGET_CANDIDATES = 20;
    private static final int FINAL_RESULT_LIMIT = 10;

    private static final int STRONG_DB_FETCH_LIMIT = 1000;
    private static final int WEAK_DB_FETCH_LIMIT = 2000;

    private static final int STABLE_TOP_K = 10;

    private static final Map<String, Double> WEIGHTS = Map.of(
            "MOOD", 0.4,
            "GENRE", 0.3,
            "ACTIVITY", 0.15,
            "BRANCH", 0.1,
            "TEMPO", 0.05
    );

    private static final String GENRE_UNKNOWN = "unknown"; // TagEnums.GENRE_UNKNOWN 쓰면 이 줄 제거 가능

    private final Random random = new Random();

    public List<SongResponseDto> recommend(LlmTagResponseDto tags) {
        try {
            return doRecommend(tags);
        } catch (BusinessException | DataAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.RECOMMENDATION_INTERNAL_ERROR,
                    "Unexpected error during recommendation: " + e.getClass().getSimpleName() + " - " + e.getMessage()
            );
        }
    }

    private List<SongResponseDto> doRecommend(LlmTagResponseDto tags) {
        if (tags == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "LlmTagResponseDto must not be null");
        }

        // 1) 후보 20개 구성 (강/약 + genre 우선 + unknown 보충)
        List<SongTag> candidateTags = buildCandidates20(tags);

        // 2) 점수화 로직은 기존 구조 유지
        List<ScoredSong> scored = candidateTags.stream()
                .map(st -> new ScoredSong(st.getSong(), computeScore(st, tags)))
                .toList();

        scored.sort(Comparator.comparingDouble(ScoredSong::score).reversed());

        // 기존 안정화 로직 유지(지금은 FINAL_RESULT_LIMIT=10이라 효과는 제한적)
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

    private List<SongTag> buildCandidates20(LlmTagResponseDto tags) {
        // Strong (genre match)
        List<SongTag> strongGenre = songTagRepository.findStrongByMoodBranchActivityTempoAndGenre(
                tags.mood(), tags.branch(), tags.activity(), tags.tempo(), tags.genre(),
                PageRequest.of(0, STRONG_DB_FETCH_LIMIT)
        );

        // Strong (unknown)
        List<SongTag> strongUnknown = songTagRepository.findStrongByMoodBranchActivityTempoAndGenre(
                tags.mood(), tags.branch(), tags.activity(), tags.tempo(), GENRE_UNKNOWN,
                PageRequest.of(0, STRONG_DB_FETCH_LIMIT)
        );

        strongGenre = dedupeBySongId(strongGenre);
        strongUnknown = dedupeBySongId(strongUnknown);

        // Case 1: 강한 조건에서 genre match가 20개 이상이면 거기서 랜덤 20
        if (strongGenre.size() >= TARGET_CANDIDATES) {
            return pickRandomSubset(strongGenre, TARGET_CANDIDATES);
        }

        // 강한 조건: genre match 전부 + 부족분을 strong unknown으로 보충
        LinkedHashMap<String, SongTag> picked = new LinkedHashMap<>();
        addAllDedup(picked, strongGenre);
        fillRandom(picked, strongUnknown, TARGET_CANDIDATES - picked.size());

        // Case 2: 강한 조건이 20개 미만이면 약한 조건으로 채움
        if (picked.size() < TARGET_CANDIDATES) {
            List<SongTag> weakGenre = songTagRepository.findWeakByMoodBranchOneMatchAndGenre(
                    tags.mood(), tags.branch(), tags.activity(), tags.tempo(), tags.genre(),
                    PageRequest.of(0, WEAK_DB_FETCH_LIMIT)
            );

            List<SongTag> weakUnknown = songTagRepository.findWeakByMoodBranchOneMatchAndGenre(
                    tags.mood(), tags.branch(), tags.activity(), tags.tempo(), GENRE_UNKNOWN,
                    PageRequest.of(0, WEAK_DB_FETCH_LIMIT)
            );

            weakGenre = dedupeBySongId(weakGenre);
            weakUnknown = dedupeBySongId(weakUnknown);

            fillRandom(picked, weakGenre, TARGET_CANDIDATES - picked.size());
            fillRandom(picked, weakUnknown, TARGET_CANDIDATES - picked.size());
        }

        // Case 3: 강+약으로도 20 미만이면 예외
        if (picked.size() < TARGET_CANDIDATES) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_NO_CANDIDATES);
        }

        return new ArrayList<>(picked.values());
    }

    private void addAllDedup(LinkedHashMap<String, SongTag> dest, List<SongTag> src) {
        for (SongTag st : src) {
            if (st == null || st.getSong() == null || st.getSong().getId() == null) continue;
            dest.putIfAbsent(st.getSong().getId(), st);
        }
    }

    private void fillRandom(LinkedHashMap<String, SongTag> picked, List<SongTag> source, int need) {
        if (need <= 0 || source == null || source.isEmpty()) return;

        Set<String> already = picked.keySet();
        List<SongTag> pool = source.stream()
                .filter(st -> st != null && st.getSong() != null && st.getSong().getId() != null)
                .filter(st -> !already.contains(st.getSong().getId()))
                .toList();

        if (pool.isEmpty()) return;

        List<SongTag> chosen = pickRandomSubset(pool, need);
        addAllDedup(picked, chosen);
    }

    private List<SongTag> dedupeBySongId(List<SongTag> list) {
        if (list == null || list.isEmpty()) return List.of();
        LinkedHashMap<String, SongTag> map = new LinkedHashMap<>();
        addAllDedup(map, list);
        return new ArrayList<>(map.values());
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
        if (source.size() <= limit) return new ArrayList<>(source);
        List<SongTag> copy = new ArrayList<>(source);
        Collections.shuffle(copy, random);
        return copy.subList(0, limit);
    }

    private record ScoredSong(Song song, double score) {}
}
