package org.example.apispring.song.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiClient {

    @Qualifier("externalApiRestTemplate")
    private final RestTemplate rt;

    @Value("${GEMINI_API_KEY:}")
    private String apiKey;

    @Value("${GEMINI_MODEL:}")
    private String model;

    @Value("${GEMINI_TEMPERATURE:0.2}")
    private double temperature;

    @Value("${GEMINI_TOP_P:0.9}")
    private double topP;

    @Value("${GEMINI_MAX_TOKENS:128}")
    private int maxTokens;

    public ResponseEntity<String> generateContent(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.GEMINI_API_KEY_MISSING);
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "Gemini model is not configured (GEMINI_MODEL)");
        }

        String url = String.format(
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
                model
        );

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                ),
                "generationConfig", Map.of(
                        "temperature", temperature,
                        "topP", topP,
                        "maxOutputTokens", maxTokens,
                        "responseMimeType", "application/json"
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("x-goog-api-key", apiKey);

        try {
            return rt.exchange(url, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
        } catch (RestClientException e) {
            throw new BusinessException(
                    ErrorCode.GEMINI_UPSTREAM_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
    }
}
