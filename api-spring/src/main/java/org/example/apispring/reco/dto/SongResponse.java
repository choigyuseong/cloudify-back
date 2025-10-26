package org.example.apispring.reco.dto;

public record SongResponse(
        String title,
        String artist,
        String videoId,
        String watchUrl,
        String embedUrl,
        String thumbnailUrl,
        double score
) {}
