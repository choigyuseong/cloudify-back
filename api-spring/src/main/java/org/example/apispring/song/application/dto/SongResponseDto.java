package org.example.apispring.song.application.dto;


import org.example.apispring.song.domain.Song;

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
                resolveImageUrl(song)
        );
    }

    private static String resolveImageUrl(Song song) {
        String album = song.getAlbumImageUrl();
        if (isUsableAlbumImage(album)) {
            return album.trim();
        }

        String thumb = song.getThumbnailImageUrl();
        if (thumb != null && !thumb.isBlank()) {
            return thumb.trim();
        }

        return null;
    }

    private static boolean isUsableAlbumImage(String album) {
        if (album == null) return false;
        String trimmed = album.trim();
        if (trimmed.isEmpty()) return false;
        if ("trash".equalsIgnoreCase(trimmed)) return false;
        return true;
    }
}
