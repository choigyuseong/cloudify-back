package org.example.apispring.recommend.web;

import lombok.RequiredArgsConstructor;
import org.example.apispring.recommend.application.GeminiService;
import org.example.apispring.recommend.application.dto.*;
import org.example.apispring.recommend.service.RecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
