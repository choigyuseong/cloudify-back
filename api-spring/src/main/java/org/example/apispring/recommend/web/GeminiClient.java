package org.example.apispring.recommend.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class GeminiClient {

    private final RestTemplate rt = new RestTemplate();

    @Value("${GEMINI_API_KEY}")
    private String apiKey;

    @Value("${GEMINI_MODEL}")
    private String model;

    @Value("${GEMINI.TEMPERATURE:0.2}")
    private double temperature;

    @Value("${GEMINI.TOP_P:0.9}")
    private double topP;

    @Value("${GEMINI.MAX_TOKENS:128}")
    private int maxTokens;

    public ResponseEntity<String> generateContent(String prompt) {
        String url = String.format(
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                model, apiKey
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

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        return rt.exchange(url, HttpMethod.POST, request, String.class);
    }
}
