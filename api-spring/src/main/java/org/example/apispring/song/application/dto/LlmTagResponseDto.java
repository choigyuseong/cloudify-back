package org.example.apispring.recommend.application.dto;

public record LlmTagResponseDto(
        String mood,
        String genre,
        String activity,
        String branch,
        String tempo
) {}
