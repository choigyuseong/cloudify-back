package org.example.apispring.song.web;


import lombok.RequiredArgsConstructor;
import org.example.apispring.song.application.GeminiService;
import org.example.apispring.song.application.RecommendationService;
import org.example.apispring.song.application.dto.LlmTagResponseDto;
import org.example.apispring.song.application.dto.LlmTextRequestDto;
import org.example.apispring.song.application.dto.SongResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
public class RecommendationController {

    private final GeminiService geminiService;
    private final RecommendationService recommendationService;

    @PostMapping("/by-text")
    public ResponseEntity<List<SongResponseDto>> recommendByText(
            @RequestBody LlmTextRequestDto request
    ) {
        LlmTagResponseDto tags = geminiService.inferTags(request.text());

        List<SongResponseDto> songs = recommendationService.recommend(tags);

        if (songs.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(songs);
    }
}
