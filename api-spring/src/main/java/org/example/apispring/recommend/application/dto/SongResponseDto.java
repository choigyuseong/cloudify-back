package org.example.apispring.recommend.application.dto;

import org.example.apispring.recommend.domain.SongRecord;

public record SongResponse(
        String title,
        String artist,
        String videoId,
        String watchUrl,
        String embedUrl,
        String thumbnailUrl,
        String albumImageUrl
) {
    public static SongResponse of(SongRecord record) {
        return new SongResponse(
                record.getTitle(),
                record.getArtist(),
                null,
                null,
                null,
                null,
                null
        );
    }
}