package org.example.apispring.recommend.domain;

import org.springframework.data.domain.Page;
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
            SELECT s 
            FROM Song s 
            WHERE s.videoId IS NULL OR s.videoId = ''           
            """)
    Page<Song> findAllByVideoIdIsNull(Pageable pageable);

    Optional<Song> findByVideoId(String videoId);
}
