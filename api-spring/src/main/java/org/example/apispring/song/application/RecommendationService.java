package org.example.apispring.song.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Slf4j
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

    private static final String GENRE_UNKNOWN = "unknown";

    public List<SongResponseDto> recommend(LlmTagResponseDto tags) {
        String rid = shortRid();
        long t0 = System.nanoTime();

        log.info("[Recommend:{}] start tags={}", rid, summarize(tags));

        try {
            List<SongResponseDto> result = doRecommend(tags, rid);
            log.info("[Recommend:{}] success resultSize={} elapsedMs={}",
                    rid, result.size(), elapsedMs(t0));
            return result;

        } catch (BusinessException e) {
            log.warn("[Recommend:{}] business_error code={} msg={}",
                    rid, e.errorCode().name(), e.getMessage());
            throw e;

        } catch (DataAccessException e) {
            log.error("[Recommend:{}] db_error type={} msg={}",
                    rid, e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;

        } catch (Exception e) {
            log.error("[Recommend:{}] unexpected_error type={} msg={}",
                    rid, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.RECOMMENDATION_INTERNAL_ERROR);
        }
    }

    private List<SongResponseDto> doRecommend(LlmTagResponseDto tags, String rid) {
        if (tags == null) {
            log.warn("[Recommend:{}] tags is null", rid);
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "LlmTagResponseDto must not be null");
        }

        // 1) 후보 20개 구성 (중복 방지)
        log.info("[Recommend:{}] buildCandidates20 begin", rid);
        List<SongTag> candidateTags = buildCandidates20(tags, rid);

        // 혹시라도 repository/로직 문제로 중복이 섞였을 때 2차 방어
        candidateTags = distinctBySongKey(candidateTags);
        log.info("[Recommend:{}] buildCandidates20 done candidatesUnique={}", rid, candidateTags.size());

        // 2) 점수화 (중복 방지: 동일 곡은 최고 점수만 유지)
        List<ScoredSong> scored = scoreDistinctSongs(candidateTags, tags, rid);

        scored.sort(Comparator.comparingDouble(ScoredSong::score).reversed());

        // 안정화(상위 K는 고정, 나머지는 셔플)
        if (scored.size() > STABLE_TOP_K) {
            List<ScoredSong> head = new ArrayList<>(scored.subList(0, STABLE_TOP_K));
            List<ScoredSong> tail = new ArrayList<>(scored.subList(STABLE_TOP_K, scored.size()));
            Collections.shuffle(tail, ThreadLocalRandom.current());

            scored = new ArrayList<>(head.size() + tail.size());
            scored.addAll(head);
            scored.addAll(tail);
        }

        // 3) 최종 10곡 반환 (중복 방지 2차: putIfAbsent)
        LinkedHashMap<String, SongResponseDto> out = new LinkedHashMap<>();
        for (ScoredSong sc : scored) {
            Song song = sc.song();
            String key = songKey(song);
            if (key == null) continue;

            out.putIfAbsent(key, SongResponseDto.of(song));
            if (out.size() >= FINAL_RESULT_LIMIT) break;
        }

        // 최종 결과가 비정상적으로 0이면 원인 파악에 도움되게 로그
        if (out.isEmpty()) {
            log.warn("[Recommend:{}] empty result after dedupe. candidates={} scored={}",
                    rid, candidateTags.size(), scored.size());
        }

        return new ArrayList<>(out.values());
    }

    private List<ScoredSong> scoreDistinctSongs(List<SongTag> candidateTags, LlmTagResponseDto tags, String rid) {
        // 동일 곡이 여러 번 들어와도 최고 점수만 남김
        Map<String, ScoredSong> bestBySong = new HashMap<>();

        for (SongTag st : candidateTags) {
            try {
                Song song = (st == null ? null : st.getSong());
                String key = songKey(song);
                if (key == null) continue;

                double s = computeScore(st, tags);

                ScoredSong prev = bestBySong.get(key);
                if (prev == null || s > prev.score()) {
                    bestBySong.put(key, new ScoredSong(song, s));
                }

            } catch (Exception e) {
                log.error("[Recommend:{}] scoring_failed songTagId={} err={} msg={}",
                        rid,
                        (st == null ? null : st.getId()),
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        e);
                throw e;
            }
        }

        return new ArrayList<>(bestBySong.values());
    }

    private List<SongTag> buildCandidates20(LlmTagResponseDto tags, String rid) {
        List<SongTag> strongGenre = fetch(rid, "strongGenre",
                () -> songTagRepository.findStrongByMoodBranchActivityTempoAndGenre(
                        tags.mood(), tags.branch(), tags.activity(), tags.tempo(), tags.genre(),
                        PageRequest.of(0, STRONG_DB_FETCH_LIMIT)
                )
        );

        List<SongTag> strongUnknown = fetch(rid, "strongUnknown",
                () -> songTagRepository.findStrongByMoodBranchActivityTempoAndGenre(
                        tags.mood(), tags.branch(), tags.activity(), tags.tempo(), GENRE_UNKNOWN,
                        PageRequest.of(0, STRONG_DB_FETCH_LIMIT)
                )
        );

        // 1차 dedupe
        strongGenre = distinctBySongKey(strongGenre);
        strongUnknown = distinctBySongKey(strongUnknown);

        log.info("[Recommend:{}] strongGenre={} strongUnknown={}", rid, strongGenre.size(), strongUnknown.size());

        // strongGenre만으로 20개 이상이면 랜덤 20개(단, 중복 없는 리스트라 안전)
        if (strongGenre.size() >= TARGET_CANDIDATES) {
            return pickRandomSubset(strongGenre, TARGET_CANDIDATES);
        }

        // 강한 조건으로 채우기
        LinkedHashMap<String, SongTag> picked = new LinkedHashMap<>();
        addAllDedupBySongKey(picked, strongGenre);
        fillRandom(picked, strongUnknown, TARGET_CANDIDATES - picked.size());

        log.info("[Recommend:{}] after strong fill picked={}", rid, picked.size());

        // 약한 조건으로 부족분 채우기
        if (picked.size() < TARGET_CANDIDATES) {
            List<SongTag> weakGenre = fetch(rid, "weakGenre",
                    () -> songTagRepository.findWeakByMoodBranchOneMatchAndGenre(
                            tags.mood(), tags.branch(), tags.activity(), tags.tempo(), tags.genre(),
                            PageRequest.of(0, WEAK_DB_FETCH_LIMIT)
                    )
            );

            List<SongTag> weakUnknown = fetch(rid, "weakUnknown",
                    () -> songTagRepository.findWeakByMoodBranchOneMatchAndGenre(
                            tags.mood(), tags.branch(), tags.activity(), tags.tempo(), GENRE_UNKNOWN,
                            PageRequest.of(0, WEAK_DB_FETCH_LIMIT)
                    )
            );

            weakGenre = distinctBySongKey(weakGenre);
            weakUnknown = distinctBySongKey(weakUnknown);

            log.info("[Recommend:{}] weakGenre={} weakUnknown={}", rid, weakGenre.size(), weakUnknown.size());

            fillRandom(picked, weakGenre, TARGET_CANDIDATES - picked.size());
            fillRandom(picked, weakUnknown, TARGET_CANDIDATES - picked.size());
        }

        if (picked.size() < TARGET_CANDIDATES) {
            log.warn("[Recommend:{}] no candidates. picked={} tags={}", rid, picked.size(), summarize(tags));
            throw new BusinessException(ErrorCode.RECOMMENDATION_NO_CANDIDATES);
        }

        return new ArrayList<>(picked.values());
    }

    private <T> List<T> fetch(String rid, String name, Supplier<List<T>> supplier) {
        long t0 = System.nanoTime();
        try {
            List<T> res = supplier.get();
            int size = (res == null ? 0 : res.size());
            log.info("[Recommend:{}] fetch {} size={} elapsedMs={}", rid, name, size, elapsedMs(t0));
            return (res == null ? List.of() : res);
        } catch (Exception e) {
            log.error("[Recommend:{}] fetch_failed {} err={} msg={}",
                    rid, name, e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }
    }

    // ------------------------------
    // Dedupe helpers (핵심)
    // ------------------------------
    private static List<SongTag> distinctBySongKey(List<SongTag> list) {
        if (list == null || list.isEmpty()) return List.of();

        LinkedHashMap<String, SongTag> map = new LinkedHashMap<>();
        for (SongTag st : list) {
            Song song = (st == null ? null : st.getSong());
            String key = songKey(song);
            if (key == null) continue;
            map.putIfAbsent(key, st);
        }
        return new ArrayList<>(map.values());
    }

    private static void addAllDedupBySongKey(LinkedHashMap<String, SongTag> dest, List<SongTag> src) {
        if (src == null || src.isEmpty()) return;

        for (SongTag st : src) {
            Song song = (st == null ? null : st.getSong());
            String key = songKey(song);
            if (key == null) continue;
            dest.putIfAbsent(key, st);
        }
    }

    private void fillRandom(LinkedHashMap<String, SongTag> picked, List<SongTag> source, int need) {
        if (need <= 0 || source == null || source.isEmpty()) return;

        Set<String> already = picked.keySet();

        List<SongTag> pool = source.stream()
                .filter(Objects::nonNull)
                .filter(st -> songKey(st.getSong()) != null)
                .filter(st -> !already.contains(songKey(st.getSong())))
                .toList();

        if (pool.isEmpty()) return;

        List<SongTag> chosen = pickRandomSubset(pool, need);
        addAllDedupBySongKey(picked, chosen);
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
        Collections.shuffle(copy, ThreadLocalRandom.current());
        return copy.subList(0, limit);
    }

    private record ScoredSong(Song song, double score) {}

    // songKey: id 우선, 없으면 artist|title로 fallback
    private static String songKey(Song s) {
        if (s == null) return null;
        if (s.getId() != null && !s.getId().isBlank()) return s.getId();

        String a = (s.getArtist() == null ? "" : s.getArtist().trim().toLowerCase(Locale.ROOT));
        String t = (s.getTitle() == null ? "" : s.getTitle().trim().toLowerCase(Locale.ROOT));
        String key = a + "|" + t;
        return key.equals("|") ? null : key;
    }

    private static String shortRid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private static String summarize(LlmTagResponseDto t) {
        if (t == null) return "null";
        return "{mood=" + t.mood()
                + ", genre=" + t.genre()
                + ", activity=" + t.activity()
                + ", branch=" + t.branch()
                + ", tempo=" + t.tempo() + "}";
    }
}
