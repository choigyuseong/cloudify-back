package org.example.apispring.song.web;

import org.example.apispring.global.config.RestTemplateConfig;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiClientRequestTest {

    @Test
    void generateContent_buildsExpectedRequest() {
        RestTemplate rt = new RestTemplateConfig().externalApiRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();

        GeminiClient client = new GeminiClient(rt);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "model", "gemini-2.0-flash");
        ReflectionTestUtils.setField(client, "temperature", 0.2d);
        ReflectionTestUtils.setField(client, "topP", 0.9d);
        ReflectionTestUtils.setField(client, "maxTokens", 128);

        String prompt = "hello";
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 핵심: 요청 바디 구조 검증
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(prompt))
                .andExpect(jsonPath("$.generationConfig.temperature").value(0.2))
                .andExpect(jsonPath("$.generationConfig.topP").value(0.9))
                .andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(128))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> res = client.generateContent(prompt);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue(res.getBody().contains("\"ok\":true"));
        server.verify();
    }

    @Test
    void generateContent_missingApiKey_throwsBusinessException() {
        RestTemplate rt = new RestTemplateConfig().externalApiRestTemplate();
        GeminiClient client = new GeminiClient(rt);

        ReflectionTestUtils.setField(client, "apiKey", ""); // 빈 값
        ReflectionTestUtils.setField(client, "model", "gemini-2.0-flash");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.generateContent("prompt"));

        assertEquals(ErrorCode.GEMINI_API_KEY_MISSING, ex.errorCode());
    }
}
