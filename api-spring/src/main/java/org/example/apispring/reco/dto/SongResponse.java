package org.example.apispring.reco.dto;

/**
 * 🎧 SongResponse
 * - 추천 결과를 반환하는 DTO (record 기반)
 * - YouTube 썸네일 + Genius 앨범 이미지 포함
 */
public record SongResponse(
        String title,
        String artist,
        String videoId,
        String watchUrl,
        String embedUrl,
        String thumbnailUrl,
        String albumImageUrl,
        double score
) {}
