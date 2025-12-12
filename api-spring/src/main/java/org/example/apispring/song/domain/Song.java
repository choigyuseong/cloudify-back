package org.example.apispring.song.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@Table(name = "songs")
public class Song {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String artist;

    @Column(nullable = false)
    private String title;

    @Column(name = "videoid")
    private String videoId;

    @Column(name = "audioid")
    private String audioId;

    @Column(name = "album_image_url")
    private String albumImageUrl;

    @Column(name = "youtube_thumbnail_url")
    private String thumbnailImageUrl;

    @Column(name = "created_at", updatable = false, insertable = false)
    private LocalDateTime createdAt;

    public void updateVideoId(String videoId) {
        this.videoId = videoId;
    }

    public void updateAlbumImageUrl(String albumImageUrl) {
        this.albumImageUrl = albumImageUrl;
    }

    public void updateThumbnailImageUrl(String url) { this.thumbnailImageUrl = url; }
}
