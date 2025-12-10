package org.example.apispring.youtube.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.youtube.application.YoutubeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/youtube")
@RequiredArgsConstructor
public class YoutubeController {
    private final YoutubeService youtubeService;

    @PostMapping("/fill-video-id")
    public ResponseEntity<String> fillVideoIds() {
        youtubeService.fillVideoIds();
        return ResponseEntity.ok("DONE");
    }
}
