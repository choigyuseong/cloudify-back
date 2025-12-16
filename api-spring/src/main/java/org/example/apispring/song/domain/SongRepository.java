package org.example.apispring.song.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SongRepository extends JpaRepository<Song, String> {

    @Query("""
            SELECT s
            FROM Song s
            WHERE s.albumImageUrl IS NULL OR s.albumImageUrl = ''
            """)
    List<Song> findSongsWithoutAlbumImage(Pageable pageable);

    @Query("""
    SELECT s FROM Song s
    WHERE (s.videoId IS NULL OR s.videoId = '')
""")
    List<Song> findSongsWithMissingVideoId(Pageable pageable);

    @Query("""
    SELECT s FROM Song s
    WHERE (s.videoId IS NOT NULL AND s.videoId <> '')
      AND (s.thumbnailImageUrl IS NULL OR s.thumbnailImageUrl = '')
""")
    List<Song> findSongsWithMissingThumbnailOnly(Pageable pageable);

    @Query("""
        SELECT s
        FROM Song s
        WHERE s.audioId IS NULL OR s.audioId = ''
        """)
    List<Song> findSongsWithMissingAudioId(Pageable pageable);

    Optional<Song> findByVideoId(String videoId);
}
