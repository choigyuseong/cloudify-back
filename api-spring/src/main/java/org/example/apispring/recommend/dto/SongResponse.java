package org.example.apispring.recommend.dto;

import org.example.apispring.recommend.domain.SongRecord;

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
) {
    // ✅ 정적 팩토리 메서드 추가
    public static SongResponse of(SongRecord record, double score) {
        return new SongResponse(
                record.getTitle(),
                record.getArtist(),
                null,       // videoId (나중에 YouTubeService에서 채워짐)
                null,       // watchUrl
                null,       // embedUrl
                null,       // thumbnailUrl
                null,       // albumImageUrl
                score
        );
    }
}