package org.example.apispring.recommend.dto;

public record CanonicalTagQuerySimple(
        String mood,
        String genre,
        String activity,
        String branch,
        String tempo
) {}
