package org.example.apispring.recommend.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

public interface SongRecordRepository extends JpaRepository<SongRecord, UUID> {

    @Query("SELECT s FROM SongRecord s WHERE " +
            "s.mood = :mood AND " +
            "s.genre = :genre AND " +
            "s.activity = :activity AND " +
            "s.branch = :branch AND " +
            "s.tempo = :tempo")
    List<SongRecord> findByAllTags(
            @Param("mood") String mood,
            @Param("genre") String genre,
            @Param("activity") String activity,
            @Param("branch") String branch,
            @Param("tempo") String tempo
    );

    // videoId == null 인 곡들 Page 단위 조회 (배치용)
    @Query("SELECT s FROM SongRecord s WHERE s.videoId IS NULL")
    Page<SongRecord> findAllByVideoIdIsNull(Pageable pageable);

    // videoId 값 업데이트 (Title + Artist 기준)
    @Modifying
    @Transactional
    @Query("UPDATE SongRecord s SET s.videoId = :videoId WHERE s.title = :title AND s.artist = :artist")
    void updateVideoId(
            @Param("title") String title,
            @Param("artist") String artist,
            @Param("videoId") String videoId
    );
}
