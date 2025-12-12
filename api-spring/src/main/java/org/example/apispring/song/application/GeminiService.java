package org.example.apispring.song.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.song.application.dto.LlmTagResponseDto;
import org.example.apispring.song.domain.TagEnums;
import org.example.apispring.song.web.GeminiClient;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class GeminiService {

    private final GeminiClient geminiClient;
    private final ObjectMapper om;

    public LlmTagResponseDto inferTags(String text) {
        String prompt = buildPrompt(text);

        ResponseEntity<String> response;
        try {
            response = geminiClient.generateContent(prompt);
        } catch (Exception ex) {
            throw new BusinessException(
                    ErrorCode.GEMINI_UPSTREAM_ERROR, "Gemini HTTP 호출 중 예외: " + ex.getClass().getSimpleName() + " - " + ex.getMessage()
            );
        }

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new BusinessException(
                    ErrorCode.GEMINI_UPSTREAM_ERROR, "LLM API 호출 실패: status=" + response.getStatusCode()
            );
        }

        JsonNode root;
        try {
            root = om.readTree(response.getBody());
        } catch (JsonProcessingException e) {
            throw new BusinessException(
                    ErrorCode.GEMINI_RESPONSE_INVALID, "LLM 응답 JSON 파싱 실패: " + e.getOriginalMessage()
            );
        }

        String generatedText = extractGeneratedText(root);
        if (generatedText == null || generatedText.isBlank()) {
            throw new BusinessException(
                    ErrorCode.GEMINI_RESPONSE_INVALID, "LLM 응답이 비어있거나 예상된 text 필드를 찾을 수 없습니다."
            );
        }

        try {
            return parseAndValidate(generatedText);
        } catch (JsonProcessingException e) {
            throw new BusinessException(
                    ErrorCode.GEMINI_TAG_JSON_PARSE_ERROR, "LLM 태그 JSON 파싱 실패: " + e.getOriginalMessage()
            );
        }
    }

    private String buildPrompt(String text) {
        return String.format("""
                당신은 음악 추천 시스템의 태그 분류기입니다.
                사용자의 자연어 입력을 분석하여 아래 형식의 JSON만 출력하세요.
                
                허용된 태그 값 목록 (모두 소문자):
                - MOOD: comfort, tender, calm, uplift, focus, wistful
                - GENRE: city_pop, ballad, acoustic, indie, lofi, pop, dance, rnb, edm
                - ACTIVITY: study, unwind, night_drive, workout, sleep
                - BRANCH: calm, uplift
                - TEMPO: slow, mid, fast
                
                출력 형식 (키는 반드시 대문자, 값은 소문자 태그 중 하나만 사용):
                {
                    "MOOD": "comfort",
                    "GENRE": "ballad",
                    "ACTIVITY": "study",
                    "BRANCH": "calm",
                    "TEMPO": "slow"
                }
                
                규칙:
                1. 각 타입(MOOD/GENRE/ACTIVITY/BRANCH/TEMPO)에서 반드시 정확히 1개의 태그를 선택하세요.
                2. 위에 제시된 허용된 태그 값 목록에 있는 값만 사용하세요.
                3. 키 이름은 반드시 대문자(MOOD, GENRE, ACTIVITY, BRANCH, TEMPO)를 사용하세요.
                4. JSON 형식만 출력하세요. 그 외 설명, 자연어 텍스트는 절대 출력하지 마세요.
                5. 충돌되는 의미가 있을 경우 더 명확한 단어를 우선하세요.
                
                사용자 입력: "%s"
                """, text);
    }

    private String extractGeneratedText(JsonNode root) {
        JsonNode candidates = root.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return null;
        }

        JsonNode content = candidates.get(0).get("content");
        if (content == null) {
            return null;
        }

        JsonNode parts = content.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            return null;
        }

        JsonNode text = parts.get(0).get("text");
        return text != null ? text.asText() : null;
    }

    private LlmTagResponseDto parseAndValidate(String generatedText) throws JsonProcessingException {
        JsonNode json = om.readTree(generatedText);

        String mood = normalizeAndValidateEnum(json, "MOOD", TagEnums.MOOD.class);
        String genre = normalizeAndValidateEnum(json, "GENRE", TagEnums.GENRE.class);
        String activity = normalizeAndValidateEnum(json, "ACTIVITY", TagEnums.ACTIVITY.class);
        String branch = normalizeAndValidateEnum(json, "BRANCH", TagEnums.BRANCH.class);
        String tempo = normalizeAndValidateEnum(json, "TEMPO", TagEnums.TEMPO.class);

        return new LlmTagResponseDto(mood, genre, activity, branch, tempo);
    }

    private <E extends Enum<E>> String normalizeAndValidateEnum(
            JsonNode json,
            String fieldNameCanonical,   // "MOOD", "GENRE", ...
            Class<E> enumType
    ) {
        JsonNode node = findFieldIgnoreCase(json, fieldNameCanonical);
        if (node == null || node.isNull()) {
            throw new BusinessException(
                    ErrorCode.GEMINI_RESPONSE_INVALID,
                    "LLM 응답에 " + fieldNameCanonical + " 필드가 없습니다."
            );
        }

        String raw = node.asText(null);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(
                    ErrorCode.GEMINI_RESPONSE_INVALID,
                    "LLM 응답의 " + fieldNameCanonical + " 값이 비어있습니다."
            );
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);

        boolean exists = Arrays.stream(enumType.getEnumConstants())
                .anyMatch(e -> e.name().equals(normalized));

        if (!exists) {
            throw new BusinessException(
                    ErrorCode.GEMINI_TAG_ENUM_MISMATCH,
                    "허용되지 않은 " + fieldNameCanonical + " 값: " + raw + " (normalized=" + normalized + ")"
            );
        }

        return normalized;
    }

    private JsonNode findFieldIgnoreCase(JsonNode json, String fieldNameCanonical) {
        if (json == null || !json.isObject()) {
            return null;
        }

        Iterator<String> fieldNames = json.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            if (key.equalsIgnoreCase(fieldNameCanonical)) {
                return json.get(key);
            }
        }
        return null;
    }
}
