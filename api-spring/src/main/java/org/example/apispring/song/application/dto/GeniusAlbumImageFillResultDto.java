package org.example.apispring.song.application.dto;

public record GeniusAlbumImageFillResultDto(
        int requestedLimit,
        int fetched,
        int success,
        int trash,
        int transientSkip,
        int failures
) {}
