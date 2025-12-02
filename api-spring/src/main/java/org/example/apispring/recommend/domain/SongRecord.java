package org.example.apispring.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Getter
@Setter  // YouTube/Genius 값 업데이트용
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "song_record")
public class SongRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    private String title;
    private String artist;

    // CSV 컬럼 기반 필드
    private String mood;
    private String genre;
    private String activity;
    private String branch;
    private String tempo;

    // ───────────── 공식 영상/공식 음원 α 점수용 필드 ─────────────
    private Boolean youtubeVerified;   // YouTube verified 여부
    private String youtubeChannel;     // 유튜브 채널 이름 → 국내 공식 채널 α 체크
    private String geniusUploader;     // Genius 업로더 → "Genius"면 공식 음원
}
