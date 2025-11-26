package org.example.apispring.recommend.dto;

import org.example.apispring.recommend.domain.SongRecord;

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
    public static SongResponse of(SongRecord record, double score) {
        return new SongResponse(
                record.getTitle(),
                record.getArtist(),
                null,
                null,
                null,
                null,
                null,
                score
        );
    }
}
