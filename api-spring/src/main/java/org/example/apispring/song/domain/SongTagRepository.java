package org.example.apispring.song.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SongTagRepository extends JpaRepository<SongTag, Long> {

    @Query("""
    SELECT st
    FROM SongTag st
    JOIN FETCH st.song s
    WHERE st.mood = :mood
      AND st.branch = :branch
      AND st.activity = :activity
      AND st.tempo = :tempo
      AND st.genre = :genre
    ORDER BY s.createdAt DESC
    """)
    List<SongTag> findStrongByMoodBranchActivityTempoAndGenre(
            @Param("mood") String mood,
            @Param("branch") String branch,
            @Param("activity") String activity,
            @Param("tempo") String tempo,
            @Param("genre") String genre,
            Pageable pageable
    );

    @Query("""
    SELECT st
    FROM SongTag st
    JOIN FETCH st.song s
    WHERE st.mood = :mood
      AND st.branch = :branch
      AND st.genre = :genre
      AND (
            (st.activity = :activity AND (st.tempo <> :tempo OR st.tempo IS NULL))
         OR (st.tempo = :tempo AND (st.activity <> :activity OR st.activity IS NULL))
      )
    ORDER BY s.createdAt DESC
    """)
    List<SongTag> findWeakByMoodBranchOneMatchAndGenre(
            @Param("mood") String mood,
            @Param("branch") String branch,
            @Param("activity") String activity,
            @Param("tempo") String tempo,
            @Param("genre") String genre,
            Pageable pageable
    );
}
