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
 * 🎵 RecommendationService
 * - LLM 기반 + PostgreSQL 기반 추천 모두 지원
 * - CSV 데이터(DB에 이관된 SongRecord 테이블) 기반
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final SongRecordRepository songRepo;
    private final YouTubeService youtubeService;

    // 🎯 태그별 가중치 (정책 기반)
    private static final Map<String, Double> WEIGHTS = Map.of(
            "MOOD", 0.4,
            "GENRE", 0.3,
            "ACTIVITY", 0.15,
            "BRANCH", 0.1,
            "TEMPO", 0.05
    );

    // ====================================================================
    // 1️⃣ LLM 기반 추천 (CanonicalTagQuery)
    // ====================================================================
    public List<SongResponse> recommend(CanonicalTagQuery query) {
        List<SongRecord> allSongs = songRepo.findAll();
        Map<SongRecord, Double> scored = new HashMap<>();

        for (SongRecord song : allSongs) {
            double score = 0.0;
            // LLM 기반의 tag.id 문자열 포함 여부 비교
            for (CanonicalTagQuery.Tag tag : query.getTags()) {
                String id = tag.id().toLowerCase();
                if (id.contains("mood") && id.contains(song.getMood().toLowerCase())) score += WEIGHTS.get("MOOD");
                if (id.contains("genre") && id.contains(song.getGenre().toLowerCase())) score += WEIGHTS.get("GENRE");
                if (id.contains("activity") && id.contains(song.getActivity().toLowerCase())) score += WEIGHTS.get("ACTIVITY");
                if (id.contains("branch") && id.contains(song.getBranch().toLowerCase())) score += WEIGHTS.get("BRANCH");
                if (id.contains("tempo") && id.contains(song.getTempo().toLowerCase())) score += WEIGHTS.get("TEMPO");
            }
            scored.put(song, score);
        }

        return scored.entrySet().stream()
                .sorted(Map.Entry.<SongRecord, Double>comparingByValue().reversed())
                .limit(30)
                .map(entry -> new SongResponse(
                        entry.getKey().getTitle(),
                        entry.getKey().getArtist(),
                        null, null, null, null, null,
                        entry.getValue()
                ))
                .collect(Collectors.toList());
    }

    // ====================================================================
    // 2️⃣ PostgreSQL 기반 추천 (CanonicalTagQuerySimple)
    // ====================================================================
    public List<SongResponse> recommend(CanonicalTagQuerySimple query) {
        List<SongRecord> allSongs = songRepo.findAll();
        Map<SongRecord, Double> scored = new HashMap<>();

        for (SongRecord song : allSongs) {
            double score = 0.0;

            if (query.mood() != null && query.mood().equalsIgnoreCase(song.getMood()))
                score += WEIGHTS.get("MOOD");
            if (query.genre() != null && query.genre().equalsIgnoreCase(song.getGenre()))
                score += WEIGHTS.get("GENRE");
            if (query.activity() != null && query.activity().equalsIgnoreCase(song.getActivity()))
                score += WEIGHTS.get("ACTIVITY");
            if (query.branch() != null && query.branch().equalsIgnoreCase(song.getBranch()))
                score += WEIGHTS.get("BRANCH");
            if (query.tempo() != null && query.tempo().equalsIgnoreCase(song.getTempo()))
                score += WEIGHTS.get("TEMPO");

            scored.put(song, score);
        }

        return scored.entrySet().stream()
                .sorted(Map.Entry.<SongRecord, Double>comparingByValue().reversed())
                .limit(30)
                .map(entry -> new SongResponse(
                        entry.getKey().getTitle(),
                        entry.getKey().getArtist(),
                        null, null, null, null, null,
                        entry.getValue()
                ))
                .collect(Collectors.toList());
    }
}
