package org.example.apispring.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Getter
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

    // ✅ CSV 컬럼 기반 필드 추가
    private String mood;
    private String genre;
    private String activity;
    private String branch;
    private String tempo;
}
