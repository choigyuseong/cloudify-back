package org.example.apispring.reco.dto;

/**
 * 🎯 CanonicalTagQuerySimple
 * - CSV→DB 기반 추천에서 사용하는 단순 태그 질의용 DTO
 */
public record CanonicalTagQuerySimple(
        String mood,
        String genre,
        String activity,
        String branch,
        String tempo
) {}
