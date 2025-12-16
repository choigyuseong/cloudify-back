package org.example.apispring.song.web;

import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class GeniusClient {

    private final RestTemplate restTemplate;

    @Value("${GENIUS_API_KEY:}")
    private String geniusToken;

    private static final String SEARCH_ENDPOINT = "https://api.genius.com/search";

    public GeniusClient(@Qualifier("externalApiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ResponseEntity<String> search(String query) {
        if (geniusToken == null || geniusToken.isBlank()) {
            log.warn("[GeniusClient] token missing");
            throw new BusinessException(ErrorCode.GENIUS_API_TOKEN_MISSING);
        }

        String url = UriComponentsBuilder.fromHttpUrl(SEARCH_ENDPOINT)
                .queryParam("q", query)
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(geniusToken);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            return restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
        } catch (RestClientException e) {
            throw new BusinessException(
                    ErrorCode.GENIUS_UPSTREAM_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
    }
}
