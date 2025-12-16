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
            // 응답 바디에는 message가 안 실릴 수 있으니(현재 핸들러 구조상) 서버 로그에 남기는 게 핵심
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

            // 응답은 기존 정책 유지: 1599(RECOMMENDATION_INTERNAL_ERROR) :contentReference[oaicite:2]{index=2}
            throw new BusinessException(ErrorCode.RECOMMENDATION_INTERNAL_ERROR);
        }
    }

    private List<SongResponseDto> doRecommend(LlmTagResponseDto tags, String rid) {
        if (tags == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "LlmTagResponseDto must not be null");
        }

        List<SongTag> candidateTags = buildCandidates20(tags, rid);

        List<ScoredSong> scored = candidateTags.stream()
                .map(st -> new ScoredSong(st.getSong(), computeScore(st, tags)))
                .toList();

        List<ScoredSong> sorted = new ArrayList<>(scored);
        sorted.sort(Comparator.comparingDouble(ScoredSong::score).reversed());

        if (sorted.size() > STABLE_TOP_K) {
            List<ScoredSong> head = new ArrayList<>(sorted.subList(0, STABLE_TOP_K));
            List<ScoredSong> tail = new ArrayList<>(sorted.subList(STABLE_TOP_K, sorted.size()));
            Collections.shuffle(tail, ThreadLocalRandom.current());

            sorted = new ArrayList<>(head.size() + tail.size());
            sorted.addAll(head);
            sorted.addAll(tail);
        }

        // ✅ 여기서 “유니크 키 기준”으로 중복 제거하며 결과 구성
        List<SongResponseDto> out = new ArrayList<>(FINAL_RESULT_LIMIT);
        Set<String> seen = new HashSet<>();

        for (ScoredSong sc : sorted) {
            Song song = sc.song();
            if (song == null) continue;

            String key = uniqueKey(song);        // << 핵심
            if (!seen.add(key)) continue;        // 이미 뽑았으면 skip

            out.add(SongResponseDto.of(song));
            if (out.size() >= FINAL_RESULT_LIMIT) break;
        }

        return out;
    }

    private String uniqueKey(Song s) {
        // 우선순위: videoId > audioId > (artist|title) > id
        if (s.getVideoId() != null && !s.getVideoId().isBlank()) return "v:" + s.getVideoId().trim();
        if (s.getAudioId() != null && !s.getAudioId().isBlank()) return "a:" + s.getAudioId().trim();

        String artist = (s.getArtist() == null) ? "" : normLite(s.getArtist());
        String title  = (s.getTitle()  == null) ? "" : normLite(s.getTitle());
        if (!artist.isBlank() || !title.isBlank()) return "t:" + artist + "|" + title;

        return "id:" + s.getId();
    }

    private String normLite(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private List<SongTag> buildCandidates20(LlmTagResponseDto tags, String rid) {
        // Strong (genre match)
        List<SongTag> strongGenre = fetch(rid, "strongGenre",
                () -> songTagRepository.findStrongByMoodBranchActivityTempoAndGenre(
                        tags.mood(), tags.branch(), tags.activity(), tags.tempo(), tags.genre(),
                        PageRequest.of(0, STRONG_DB_FETCH_LIMIT)
                )
        );

        // Strong (unknown)
        List<SongTag> strongUnknown = fetch(rid, "strongUnknown",
                () -> songTagRepository.findStrongByMoodBranchActivityTempoAndGenre(
                        tags.mood(), tags.branch(), tags.activity(), tags.tempo(), GENRE_UNKNOWN,
                        PageRequest.of(0, STRONG_DB_FETCH_LIMIT)
                )
        );

        strongGenre = dedupeBySongId(strongGenre);
        strongUnknown = dedupeBySongId(strongUnknown);

        log.info("[Recommend:{}] strongGenre={} strongUnknown={}", rid, strongGenre.size(), strongUnknown.size());

        if (strongGenre.size() >= TARGET_CANDIDATES) {
            List<SongTag> picked = pickRandomSubset(strongGenre, TARGET_CANDIDATES);
            log.info("[Recommend:{}] picked from strongGenre only: {}", rid, picked.size());
            return picked;
        }

        LinkedHashMap<String, SongTag> picked = new LinkedHashMap<>();
        addAllDedup(picked, strongGenre);
        fillRandom(picked, strongUnknown, TARGET_CANDIDATES - picked.size());

        log.info("[Recommend:{}] after strong fill picked={}", rid, picked.size());

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

            weakGenre = dedupeBySongId(weakGenre);
            weakUnknown = dedupeBySongId(weakUnknown);

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
        Collections.shuffle(copy, ThreadLocalRandom.current());
        return copy.subList(0, limit);
    }

    private record ScoredSong(Song song, double score) {}

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

    private static String safeId(Object id) {
        return String.valueOf(id);
    }
}
