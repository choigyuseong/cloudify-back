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
        } catch (BusinessException be) {
            throw be;
        } catch (Exception ex) {
            throw new BusinessException(
                    ErrorCode.GEMINI_UPSTREAM_ERROR,
                    "Gemini HTTP 호출 중 예외: " + ex.getClass().getSimpleName() + " - " + ex.getMessage()
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
        String moods = joinEnum(TagEnums.MOOD.class);
        String genres = joinEnum(TagEnums.GENRE.class);
        String activities = joinEnum(TagEnums.ACTIVITY.class);
        String branches = joinEnum(TagEnums.BRANCH.class);
        String tempos = joinEnum(TagEnums.TEMPO.class);

        return String.format("""
            당신은 음악 추천 시스템의 태그 분류기입니다.
            사용자의 자연어 입력을 분석하여 아래 형식의 JSON만 출력하세요.

            허용된 태그 값 목록 (모두 소문자):
            - MOOD: %s
            - GENRE: %s
            - ACTIVITY: %s
            - BRANCH: %s
            - TEMPO: %s

            출력 형식 (키는 반드시 대문자, 값은 소문자 태그 중 하나만 사용):
            {
                "MOOD": "%s",
                "GENRE": "%s",
                "ACTIVITY": "%s",
                "BRANCH": "%s",
                "TEMPO": "%s"
            }

            규칙:
            1. 각 타입에서 정확히 1개만 선택
            2. 허용 목록 밖 값 금지
            3. 키는 대문자, 값은 소문자
            4. JSON 외 텍스트 출력 금지
            5. "unknown"은 출력 금지

            사용자 입력: "%s"
            """,
                moods, genres, activities, branches, tempos,
                TagEnums.MOOD.values()[0], TagEnums.GENRE.values()[0], TagEnums.ACTIVITY.values()[0],
                TagEnums.BRANCH.values()[0], TagEnums.TEMPO.values()[0],
                text
        );
    }

    private static <E extends Enum<E>> String joinEnum(Class<E> t) {
        return java.util.Arrays.stream(t.getEnumConstants())
                .map(Enum::name)
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static String extractJsonObject(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();

        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "");
            t = t.replaceFirst("\\s*```$", "");
            t = t.trim();
        }

        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            t = t.substring(start, end + 1);
        }
        return t.trim();
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
        String jsonText = extractJsonObject(generatedText);
        JsonNode json = om.readTree(jsonText);

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

        String normalized = canonicalizeTagValue(fieldNameCanonical, raw);

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

    private static String canonicalizeTagValue(String fieldNameCanonical, String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);

        // 1) 가장 흔한 원형 표현 alias (공백 포함)
        s = applyAlias(fieldNameCanonical, s);

        // 2) 공백/하이픈 => underscore
        s = s.replaceAll("[\\s\\-]+", "_");

        // 3) underscore 변환 이후 alias (lo_fi 등)
        s = applyAlias(fieldNameCanonical, s);

        // 4) 나머지 특수문자 제거 (underscore 는 유지)
        s = s.replaceAll("[^a-z0-9_]", "");

        // 5) underscore 정리
        s = s.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return s;
    }

    private static String applyAlias(String fieldNameCanonical, String candidate) {
        // NOTE: alias 는 최소로 둡니다. (정규화 후에도 카논 값으로 매핑이 어려운 케이스만)
        return switch (fieldNameCanonical) {
            case "GENRE" -> switch (candidate) {
                case "r&b", "r and b", "randb", "rnb" -> "rnb";
                case "lo-fi", "lo fi", "lo_fi" -> "lofi";
                case "city pop", "city-pop" -> "city_pop";
                default -> candidate;
            };
            case "ACTIVITY" -> switch (candidate) {
                case "night drive", "night-drive" -> "night_drive";
                default -> candidate;
            };
            default -> candidate;
        };
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
