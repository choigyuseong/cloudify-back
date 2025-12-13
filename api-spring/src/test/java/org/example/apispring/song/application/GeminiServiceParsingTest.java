package org.example.apispring.song.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.apispring.global.config.RestTemplateConfig;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.song.application.dto.LlmTagResponseDto;
import org.example.apispring.song.web.GeminiClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiServiceParsingTest {

    @Test
    void inferTags_parsesGeminiResponseIntoDto() {
        RestTemplate rt = new RestTemplateConfig().externalApiRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();

        GeminiClient client = new GeminiClient(rt);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "model", "gemini-2.0-flash");

        GeminiService service = new GeminiService(client, new ObjectMapper());

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

        // Gemini 응답: candidates[0].content.parts[0].text 안에 "문자열 JSON"로 태그가 들어오는 형태
        String geminiResponse = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\\"MOOD\\":\\"Comfort\\",\\"GENRE\\":\\"Ballad\\",\\"ACTIVITY\\":\\"Study\\",\\"BRANCH\\":\\"Calm\\",\\"TEMPO\\":\\"Slow\\"}"
                  }
                ]
              }
            }
          ]
        }
        """;

        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").exists())
                .andRespond(withSuccess(geminiResponse, MediaType.APPLICATION_JSON));

        LlmTagResponseDto dto = service.inferTags("내일 소풍가는데, 행복해질수 있는 곡 추천해줘");

        // service가 소문자로 normalize 하므로 (Comfort -> comfort)
        assertEquals("comfort", dto.mood());
        assertEquals("ballad", dto.genre());
        assertEquals("study", dto.activity());
        assertEquals("calm", dto.branch());
        assertEquals("slow", dto.tempo());

        server.verify();
    }

    @Test
    void inferTags_non2xxFromGemini_throwsUpstreamError() {
        RestTemplate rt = new RestTemplateConfig().externalApiRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();

        GeminiClient client = new GeminiClient(rt);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "model", "gemini-2.0-flash");

        GeminiService service = new GeminiService(client, new ObjectMapper());

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"invalid api key\"}}"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.inferTags("test"));

        assertEquals(ErrorCode.GEMINI_UPSTREAM_ERROR, ex.errorCode());
        server.verify();
    }
}
