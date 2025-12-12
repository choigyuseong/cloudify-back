package org.example.apispring.song.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeniusClient {

    private final RestTemplate restTemplate;

    @Value("${GENIUS_API_KEY:}")
    private String geniusToken;

    private static final String SEARCH_ENDPOINT = "https://api.genius.com/search";

    public ResponseEntity<String> search(String query) {
        if (geniusToken == null || geniusToken.isBlank()) {
            log.warn("[GeniusClient] token missing");
            throw new BusinessException(ErrorCode.GENIUS_API_TOKEN_MISSING);
        }

        String url = UriComponentsBuilder.fromHttpUrl(SEARCH_ENDPOINT)
                .queryParam("q", query)
                .build(false)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(geniusToken);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        return restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
    }
}
