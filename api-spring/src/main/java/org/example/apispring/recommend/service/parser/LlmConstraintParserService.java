package org.example.apispring.recommend.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.recommend.domain.TagEnums.*;
import org.example.apispring.recommend.dto.CanonicalTagQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.nio.charset.StandardCharsets;


import java.util.*;
import java.util.stream.Collectors;

/**
 * Gemini API를 REST 방식으로 호출하여 카논 태그 제약 JSON을 받아오는 서비스
 * - Vertex AI SDK 대신 직접 REST API 호출
 * - 화이트리스트 검증 및 타입별 1개 제한
 * - 실패/타임아웃 시 간단 폴백 리턴
 */
@Slf4j
@Service
public class LlmConstraintParserService implements ConstraintParserService {

    private final RestTemplate http;
    private final ObjectMapper om = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final double temperature;
    private final double topP;
    private final int maxTokens;

    // 서버측 화이트리스트
    private static final Set<String> MOODS = Arrays.stream(MOOD.values())
            .map(v -> "mood." + v.name().toLowerCase()).collect(Collectors.toSet());
    private static final Set<String> GENRES = Arrays.stream(GENRE.values())
            .map(v -> "genre." + v.name().toLowerCase()).collect(Collectors.toSet());
    private static final Set<String> ACTIVITIES = Arrays.stream(ACTIVITY.values())
            .map(v -> "activity." + v.name().toLowerCase()).collect(Collectors.toSet());
    private static final Set<String> BRANCHES = Arrays.stream(BRANCH.values())
            .map(v -> "branch." + v.name().toLowerCase()).collect(Collectors.toSet());
    private static final Set<String> TEMPOS = Arrays.stream(TEMPO.values())
            .map(v -> "tempo." + v.name().toLowerCase()).collect(Collectors.toSet());

    public LlmConstraintParserService(
            RestTemplate restTemplate,
            @Value("${cloudify.llm.apiKey}") String apiKey,
            @Value("${cloudify.llm.model:gemini-2.0-flash-exp}") String model,
            @Value("${cloudify.llm.temperature:0.2}") double temperature,
            @Value("${cloudify.llm.topP:0.9}") double topP,
            @Value("${cloudify.llm.maxTokens:500}") int maxTokens
    ) {
        this.http = restTemplate;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
    }

    @Override
    public CanonicalTagQuery parseToCanonicalTags(String text, String locale) {
        try {
            log.info("🤖 Gemini API 호출 시작: text='{}'", text);

            // Gemini API URL
            String url = String.format(
                    "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                    model, apiKey
            );

            // 프롬프트 구성
            String prompt = buildPrompt(text, locale);

            // 요청 body 구성
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

            // API 호출
            ResponseEntity<String> response = http.exchange(url, HttpMethod.POST, request, String.class);

            // 로그 추가 (UTF-8 확인)
            if (response.getBody() != null) {
                byte[] rawBytes = response.getBody().getBytes(StandardCharsets.UTF_8);
                String utf8 = new String(rawBytes, StandardCharsets.UTF_8);
                log.info("🔥 Gemini raw UTF-8 response: {}", utf8);
            }

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("⚠️ Gemini API 응답 실패: status={}", response.getStatusCode());
                return fallback(text);
            }

            // 응답 파싱
            JsonNode root = om.readTree(response.getBody());
            String generatedText = extractGeneratedText(root);

            if (generatedText == null || generatedText.isBlank()) {
                log.warn("⚠️ Gemini API 응답이 비어있음");
                return fallback(text);
            }

            log.info("✅ Gemini 응답: {}", generatedText);

            // JSON 파싱
            JsonNode tagJson = om.readTree(generatedText);
            List<String> ids = new ArrayList<>();

            if (tagJson.has("tags") && tagJson.get("tags").isArray()) {
                for (JsonNode t : tagJson.get("tags")) {
                    String id = null;
                    if (t.isTextual()) {
                        id = t.asText();
                    } else if (t.has("id")) {
                        id = t.get("id").asText(null);
                    }
                    if (id != null && !id.isBlank()) {
                        ids.add(id.trim().toLowerCase());
                    }
                }
            }

            List<CanonicalTagQuery.Tag> filtered = validateAndFilter(ids);
            if (filtered.size() < 2) {
                log.warn("⚠️ 유효한 태그 부족 ({}개), 폴백 사용", filtered.size());
                return fallback(text);
            }

            CanonicalTagQuery.Keywords kw = new CanonicalTagQuery.Keywords(List.of(), List.of());
            CanonicalTagQuery.Filters ff = new CanonicalTagQuery.Filters(false, false);

            log.info("✅ 파싱 성공: {} 태그", filtered.size());
            return new CanonicalTagQuery(filtered, kw, ff);

        } catch (Exception e) {
            log.error("❌ Gemini API 호출 실패", e);
            return fallback(text);
        }
    }

    /**
     * Gemini 프롬프트 구성
     */
    private String buildPrompt(String text, String locale) {
        return String.format("""
        당신은 음악 추천 시스템의 태그 분류기입니다.
        사용자의 자연어 입력을 분석하여 아래 형식의 JSON만 출력하세요.
        
        허용된 태그:
        - mood: comfort, tender, calm, uplift, focus, wistful
        - genre: city_pop, ballad, acoustic, indie, lofi, pop, dance, rnb, edm
        - activity: study, unwind, night_drive, workout, sleep
        - branch: calm, uplift
        - tempo: slow, mid, fast
        
        출력 형식 (반드시 다음 JSON 형태 유지):
        {
          "tags": [
            {"id": "mood.xxx"},
            {"id": "genre.xxx"},
            {"id": "activity.xxx"},
            {"id": "branch.xxx"},
            {"id": "tempo.xxx"}
          ]
        }
        
        규칙:
        1. 각 타입(mood/genre/activity/branch/tempo)에서 정확히 한 개의 태그만 선택
        2. 허용된 태그 외 단어 절대 사용 금지
        3. JSON 외의 텍스트 절대 출력 금지 (설명/문장/주석 포함 금지)
        4. 입력이 모호하거나 여러 해석이 가능한 경우 default 선택:
            - mood: comfort
            - genre: pop
            - activity: unwind
            - branch: calm
            - tempo: mid
        5. 충돌되는 의미가 있을 경우 더 명확한 단어를 우선
        
        사용자 입력: "%s"
        """, text);
    }

    /**
     * Gemini 응답에서 생성된 텍스트 추출
     */
    private String extractGeneratedText(JsonNode root) {
        try {
            JsonNode candidates = root.get("candidates");
            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                JsonNode content = candidates.get(0).get("content");
                if (content != null) {
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray() && parts.size() > 0) {
                        JsonNode text = parts.get(0).get("text");
                        if (text != null) {
                            return text.asText();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Gemini 응답 파싱 실패", e);
        }
        return null;
    }

    /**
     * 태그 화이트리스트 검증 + 타입별 1개 제한
     */
    private List<CanonicalTagQuery.Tag> validateAndFilter(List<String> idsRaw) {
        if (idsRaw == null || idsRaw.isEmpty()) {
            return List.of();
        }

        String mood = null, genre = null, activity = null, branch = null, tempo = null;

        for (String id : idsRaw) {
            String value = id.contains(".") ? id.split("\\.")[1] : id; // prefix 제거

            if (mood == null && MOODS.contains("mood." + value)) {
                mood = value;  // DB 컬럼과 동일하게 prefix 제거된 값 사용
            } else if (genre == null && GENRES.contains("genre." + value)) {
                genre = value;
            } else if (activity == null && ACTIVITIES.contains("activity." + value)) {
                activity = value;
            } else if (branch == null && BRANCHES.contains("branch." + value)) {
                branch = value;
            } else if (tempo == null && TEMPOS.contains("tempo." + value)) {
                tempo = value;
            }
        }

        List<CanonicalTagQuery.Tag> out = new ArrayList<>();
        if (mood != null) out.add(new CanonicalTagQuery.Tag(mood));
        if (genre != null) out.add(new CanonicalTagQuery.Tag(genre));
        if (activity != null) out.add(new CanonicalTagQuery.Tag(activity));
        if (branch != null) out.add(new CanonicalTagQuery.Tag(branch));
        if (tempo != null) out.add(new CanonicalTagQuery.Tag(tempo));

        return out;
    }

    /**
     * 실패/타임아웃 폴백
     */
    private CanonicalTagQuery fallback(String text) {
        log.info("🔄 폴백 모드 사용");

        List<CanonicalTagQuery.Tag> tags = new ArrayList<>();
        tags.add(new CanonicalTagQuery.Tag("mood.comfort"));
        tags.add(new CanonicalTagQuery.Tag("genre.ballad"));

        String s = (text == null ? "" : text.toLowerCase(Locale.ROOT));
        if (s.contains("신나") || s.contains("uplift") || s.contains("업") || s.contains("빠른")) {
            tags.add(new CanonicalTagQuery.Tag("branch.uplift"));
            tags.add(new CanonicalTagQuery.Tag("tempo.fast"));
        } else {
            tags.add(new CanonicalTagQuery.Tag("branch.calm"));
            tags.add(new CanonicalTagQuery.Tag("tempo.slow"));
        }

        CanonicalTagQuery.Keywords kw = new CanonicalTagQuery.Keywords(List.of(), List.of());
        CanonicalTagQuery.Filters ff = new CanonicalTagQuery.Filters(false, false);

        return new CanonicalTagQuery(tags, kw, ff);
    }
}