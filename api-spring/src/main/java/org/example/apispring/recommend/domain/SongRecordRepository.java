package org.example.apispring.recommend.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
