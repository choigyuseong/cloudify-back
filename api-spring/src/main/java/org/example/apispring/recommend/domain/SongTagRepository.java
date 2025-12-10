package org.example.apispring.recommend.domain;

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
          AND (
                (CASE WHEN st.genre    = :genre    THEN 1 ELSE 0 END) +
                (CASE WHEN st.activity = :activity THEN 1 ELSE 0 END) +
                (CASE WHEN st.tempo    = :tempo    THEN 1 ELSE 0 END)
              ) >= :minOptionalMatchCount
        ORDER BY
              (CASE WHEN st.genre    = :genre    THEN 1 ELSE 0 END) +
              (CASE WHEN st.activity = :activity THEN 1 ELSE 0 END) +
              (CASE WHEN st.tempo    = :tempo    THEN 1 ELSE 0 END) DESC,
              s.createdAt DESC
        """)
    List<SongTag> findByMoodBranchAndOptionalMatchesAtLeast(
            @Param("mood") String mood,
            @Param("genre") String genre,
            @Param("activity") String activity,
            @Param("branch") String branch,
            @Param("tempo") String tempo,
            @Param("minOptionalMatchCount") int minOptionalMatchCount,
            Pageable pageable
    );
}
