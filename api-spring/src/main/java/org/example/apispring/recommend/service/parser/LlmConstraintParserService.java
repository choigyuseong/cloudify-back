//package org.example.apispring.recommend.service.parser;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.example.apispring.recommend.domain.TagEnums.*;
//import org.example.apispring.recommend.dto.CanonicalTagQuery;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.*;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.RestTemplate;
//
//import java.util.*;
//import java.util.stream.Collectors;
//
///**
// * 외부 LLM에 사용자 문장을 보내서 카논 태그 제약 JSON을 받아오는 서비스.
// * - RestTemplate 사용 (webflux 불필요)
// * - 화이트리스트 검증 및 타입별 1개 제한
// * - 실패/타임아웃 시 간단 폴백 리턴
// */
//@Service
//public class LlmConstraintParserService implements ConstraintParserService {
//
//    private final RestTemplate http = new RestTemplate();
//    private final ObjectMapper om = new ObjectMapper();
//
//    private final String baseUrl;
//    private final String path;
//    private final String apiKey;
//    private final long timeoutMs;
//
//    private static final Set<String> MOODS = Arrays.stream(MOOD.values()).map(v -> "mood." + v.name()).collect(Collectors.toSet());
//    private static final Set<String> GENRES = Arrays.stream(GENRE.values()).map(v -> "genre." + v.name()).collect(Collectors.toSet());
//    private static final Set<String> ACTIVITIES = Arrays.stream(ACTIVITY.values()).map(v -> "activity." + v.name()).collect(Collectors.toSet());
//    private static final Set<String> BRANCHES = Arrays.stream(BRANCH.values()).map(v -> "branch." + v.name()).collect(Collectors.toSet());
//    private static final Set<String> TEMPOS = Arrays.stream(TEMPO.values()).map(v -> "tempo." + v.name()).collect(Collectors.toSet());
//
//    public LlmConstraintParserService(
//            @Value("${cloudify.llm.baseUrl}") String baseUrl,
//            @Value("${cloudify.llm.path:/parse}") String path,
//            @Value("${cloudify.llm.apiKey:}") String apiKey,
//            @Value("${cloudify.llm.timeoutMs:2000}") long timeoutMs
//    ) {
//        this.baseUrl = baseUrl;
//        this.path = path;
//        this.apiKey = apiKey;
//        this.timeoutMs = timeoutMs;
//    }
//
//    @Override
//    public CanonicalTagQuery parseToCanonicalTags(String text, String locale) {
//        try {
//            String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path : baseUrl + path;
//
//            HttpHeaders headers = new HttpHeaders();
//            headers.setContentType(MediaType.APPLICATION_JSON);
//            if (apiKey != null && !apiKey.isBlank()) {
//                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey); // 필요 시 헤더명 조정
//            }
//
//            LlmRequest reqBody = new LlmRequest(text, (locale == null || locale.isBlank()) ? "ko-KR" : locale);
//            String json = om.writeValueAsString(reqBody);
//            HttpEntity<String> req = new HttpEntity<>(json, headers);
//
//            ResponseEntity<String> res = http.exchange(url, HttpMethod.POST, req, String.class);
//
//            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null || res.getBody().isBlank()) {
//                return fallback(text);
//            }
//
//            // 응답 파싱 (유연하게)
//            JsonNode root = om.readTree(res.getBody());
//            List<String> ids = new ArrayList<>();
//            if (root.has("tags") && root.get("tags").isArray()) {
//                for (JsonNode t : root.get("tags")) {
//                    String id = null;
//                    if (t.isTextual()) id = t.asText();
//                    else if (t.has("id")) id = t.get("id").asText(null);
//                    if (id != null && !id.isBlank()) ids.add(id.trim().toLowerCase());
//                }
//            }
//
//            List<CanonicalTagQuery.Tag> filtered = validateAndFilter(ids);
//            if (filtered.size() < 2) return fallback(text);
//
//            CanonicalTagQuery.Keywords kw = new CanonicalTagQuery.Keywords(List.of(), List.of());
//            CanonicalTagQuery.Filters  ff = new CanonicalTagQuery.Filters(false, false);
//            return new CanonicalTagQuery(filtered, kw, ff);
//
//        } catch (Exception e) {
//            return fallback(text);
//        }
//    }
//
//    /** 태그 화이트리스트 검증 + 타입별 1개 제한 */
//    private List<CanonicalTagQuery.Tag> validateAndFilter(List<String> idsRaw) {
//        if (idsRaw == null) return List.of();
//        String mood = null, genre = null, activity = null, branch = null, tempo = null;
//
//        for (String id : idsRaw) {
//            if (mood == null && MOODS.contains(id)) mood = id;
//            else if (genre == null && GENRES.contains(id)) genre = id;
//            else if (activity == null && ACTIVITIES.contains(id)) activity = id;
//            else if (branch == null && BRANCHES.contains(id)) branch = id;
//            else if (tempo == null && TEMPOS.contains(id)) tempo = id;
//        }
//
//        List<CanonicalTagQuery.Tag> out = new ArrayList<>();
//        if (mood != null) out.add(new CanonicalTagQuery.Tag(mood));
//        if (genre != null) out.add(new CanonicalTagQuery.Tag(genre));
//        if (activity != null) out.add(new CanonicalTagQuery.Tag(activity));
//        if (branch != null) out.add(new CanonicalTagQuery.Tag(branch));
//        if (tempo != null) out.add(new CanonicalTagQuery.Tag(tempo));
//        return out;
//    }
//
//    /** 실패/타임아웃 폴백 */
//    private CanonicalTagQuery fallback(String text) {
//        List<CanonicalTagQuery.Tag> tags = new ArrayList<>();
//        tags.add(new CanonicalTagQuery.Tag("mood.comfort"));
//        tags.add(new CanonicalTagQuery.Tag("genre.ballad"));
//
//        String s = (text == null ? "" : text.toLowerCase(Locale.ROOT));
//        if (s.contains("신나") || s.contains("uplift") || s.contains("업")) {
//            tags.add(new CanonicalTagQuery.Tag("branch.uplift"));
//            tags.add(new CanonicalTagQuery.Tag("tempo.fast"));
//        } else {
//            tags.add(new CanonicalTagQuery.Tag("branch.calm"));
//            tags.add(new CanonicalTagQuery.Tag("tempo.slow"));
//        }
//        CanonicalTagQuery.Keywords kw = new CanonicalTagQuery.Keywords(List.of(), List.of());
//        CanonicalTagQuery.Filters  ff = new CanonicalTagQuery.Filters(false, false);
//        return new CanonicalTagQuery(tags, kw, ff);
//    }
//
//    /** LLM 입력 모델 */
//    public record LlmRequest(String text, String locale) {}
//}
