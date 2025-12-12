package org.example.apispring.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@Table(name = "tags")
public class SongTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "song_id", nullable = false)
    private Song song;

    @Column(name = "mood", nullable = false)
    private String mood;

    @Column(name = "genre")
    private String genre;

    @Column(name = "activity")
    private String activity;

    @Column(name = "branch")
    private String branch;

    @Column(name = "tempo")
    private String tempo;

    @Column(name = "created_at", updatable = false, insertable = false)
    private LocalDateTime createdAt;
}
