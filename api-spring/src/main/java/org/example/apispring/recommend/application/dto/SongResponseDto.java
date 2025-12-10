package org.example.apispring.recommend.application.dto;


import org.example.apispring.recommend.domain.Song;

public record SongResponseDto(
        String title,
        String artist,
        String videoId,
        String songImageUrl
) {
    public static SongResponseDto of(Song song) {
        return new SongResponseDto(
                song.getTitle(),
                song.getArtist(),
                song.getVideoId(),
                null
        );
    }
}