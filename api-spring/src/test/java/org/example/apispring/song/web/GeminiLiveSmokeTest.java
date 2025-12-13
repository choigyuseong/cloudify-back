package org.example.apispring.song.web;

import org.example.apispring.global.config.RestTemplateConfig;
import org.example.apispring.global.error.BusinessException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.fail;

class GeminiLiveSmokeTest {

    @Test
    @Tag("live")
    void live_generateContent_printStatusAndBody() {
        // 실수로 CI/일반 테스트에서 호출되지 않게 “명시적 플래그”로만 실행
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("RUN_LIVE_TESTS")),
                "Set RUN_LIVE_TESTS=true to enable live Gemini call"
        );

        String apiKey = System.getenv("GEMINI_API_KEY");
        String model = System.getenv("GEMINI_MODEL");

        if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            fail("Missing env vars. Set GEMINI_API_KEY and GEMINI_MODEL before running this test.");
        }

        RestTemplate rt = new RestTemplateConfig().externalApiRestTemplate();

        GeminiClient client = new GeminiClient(rt);
        // Spring context 생략 상태이므로 @Value 필드 주입을 테스트에서 대체
        ReflectionTestUtils.setField(client, "apiKey", apiKey);
        ReflectionTestUtils.setField(client, "model", model);
        ReflectionTestUtils.setField(client, "temperature", 0.2d);
        ReflectionTestUtils.setField(client, "topP", 0.9d);
        ReflectionTestUtils.setField(client, "maxTokens", 128);

        String prompt = """
                아래 JSON만 출력해. 다른 설명 금지.
                {"MOOD":"comfort","GENRE":"ballad","ACTIVITY":"study","BRANCH":"calm","TEMPO":"slow"}
                사용자 입력: "내일 소풍가는데, 행복해질수 있는 곡 추천해줘"
                """;

        try {
            ResponseEntity<String> res = client.generateContent(prompt);

            String body = res.getBody();
            System.out.println("[Gemini] status = " + res.getStatusCode());
            System.out.println("[Gemini] body   = " + truncate(body, 4000));

            if (!res.getStatusCode().is2xxSuccessful()) {
                fail("Gemini returned non-2xx: status=" + res.getStatusCode()
                        + ", body=" + truncate(body, 4000));
            }
        } catch (BusinessException be) {
            // 여기로 오면 보통 네트워크/DNS/타임아웃 등 RestClientException 계열 가능성이 큼
            System.out.println("[Gemini] BusinessException: " + be.errorCode() + " / " + be.getMessage());
            throw be;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(truncated, len=" + s.length() + ")";
    }
}
