package org.example.apispring.song.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class YoutubeClient {

    private final RestTemplate restTemplate;

    @Value("${YOUTUBE_API_KEY:}")
    private String apiKey;

    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";

    public ResponseEntity<String> search(String query, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.YOUTUBE_API_KEY_MISSING);
        }

        String url = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                .queryParam("part", "snippet")
                .queryParam("q", query)
                .queryParam("type", "video")
                .queryParam("maxResults", Math.max(1, maxResults))
                .queryParam("key", apiKey)
                .build(false)
                .toUriString();

        return restTemplate.getForEntity(url, String.class);
    }
}
