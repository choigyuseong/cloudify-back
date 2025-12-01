package org.example.apispring.recommend.service;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

<<<<<<< HEAD:api-spring/src/main/java/org/example/apispring/reco/service/GeniusService.java
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Slf4j
=======
>>>>>>> c0e0d7b8e38009d428738b37315c9116f19884b6:api-spring/src/main/java/org/example/apispring/recommend/service/GeniusService.java
@Service
public class GeniusService {

    private final RestTemplate http = new RestTemplate();

    @Value("${genius.api.key}")
    private String geniusToken;

<<<<<<< HEAD:api-spring/src/main/java/org/example/apispring/reco/service/GeniusService.java
    /**
     * 기존 호출과의 호환용(예산 가드 없이 호출).
     * 정확한 아트워크 URL을 반환(없으면 null).
     */
=======
>>>>>>> c0e0d7b8e38009d428738b37315c9116f19884b6:api-spring/src/main/java/org/example/apispring/recommend/service/GeniusService.java
    public String fetchAlbumImage(String title, String artist) {
        return fetchAlbumImage(title, artist, null);
    }

    /**
     * 요청 예산 가드까지 포함한 버전(폭주 방지용).
     * RequestBudget가 null이면 예산 가드 없이 동작.
     */
    public String fetchAlbumImage(String title, String artist, org.example.apispring.reco.service.common.RequestBudget budget) {
        try {
            if (budget != null && !budget.takeOne()) {
                // 예산 소진 → 외부 호출 생략
                return null;
            }

            if (geniusToken == null || geniusToken.isBlank()) {
                log.warn("[Genius] token missing");
                return null;
            }

            String q = (isBlank(artist) ? title : (artist + " " + title));
            String url = "https://api.genius.com/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + geniusToken);

            ResponseEntity<String> res = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) return null;

            JSONObject root = new JSONObject(res.getBody());
            JSONObject resp = root.optJSONObject("response");
            if (resp == null) return null;

            JSONArray hits = resp.optJSONArray("hits");
            if (hits == null || hits.isEmpty()) return null;

            // 정규화
            String wantTitle = norm(title);
            String wantArtist = norm(artist);

            JSONObject best = null;
            double bestScore = -1.0;

            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.optJSONObject(i);
                if (hit == null) continue;
                JSONObject result = hit.optJSONObject("result");
                if (result == null) continue;

                String art = pickArtUrl(result);
                if (art == null) continue; // 이미지가 없으면 스킵

                String primaryArtist = norm(primaryArtistName(result));
                String rTitle = norm(result.optString("title", ""));
                String fullTitle = norm(result.optString("full_title", ""));

                double s = 0.0;

                // 1) 아티스트 정합성 (최우선)
                if (!wantArtist.isEmpty() && !primaryArtist.isEmpty()) {
                    if (primaryArtist.equals(wantArtist)) s += 0.60;
                    else if (primaryArtist.contains(wantArtist) || wantArtist.contains(primaryArtist)) s += 0.45;
                    else continue; // 아티스트가 너무 다르면 탈락
                }

                // 2) 제목 정합성
                if (!wantTitle.isEmpty()) {
                    if (rTitle.equals(wantTitle)) s += 0.30;
                    else if (rTitle.contains(wantTitle) || fullTitle.contains(wantTitle)) s += 0.20;
                }

                // 3) 잡음 패널티(라이브/커버/리믹스/가사 영상 등)
                String noisy = (rTitle + " " + fullTitle);
                if (noisy.matches(".*\\b(live|cover|remix|nightcore|sped up|lyrics)\\b.*")) s -= 0.25;

                if (s > bestScore) {
                    bestScore = s;
                    best = result;
                }
            }

            if (best != null) {
                String art = pickArtUrl(best);
                if (art != null) return art;
            }

            // 최후 fallback: 첫 hit에서라도 아트가 있으면 리턴(싫으면 이 블록 제거)
            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.optJSONObject(i);
                if (hit == null) continue;
                JSONObject result = hit.optJSONObject("result");
                String art = pickArtUrl(result);
                if (art != null) return art;
            }
            return null;

        } catch (Exception e) {
            log.warn("[Genius] error: {}", e.toString());
            return null;
        }
    }

    // ===================== 유틸 =====================

    private String pickArtUrl(JSONObject result) {
        if (result == null) return null;
        String[] keys = new String[]{
                "song_art_image_url",
                "song_art_image_thumbnail_url",
                "header_image_url"
        };
        for (String k : keys) {
            String v = result.optString(k, null);
            if (isHttp(v)) return v;
        }
        return null;
    }

    private String primaryArtistName(JSONObject result) {
        try {
            JSONObject pa = result.optJSONObject("primary_artist");
            if (pa != null) return pa.optString("name", "");
        } catch (Exception ignored) {}
        return "";
    }

    private boolean isHttp(String s) { return s != null && (s.startsWith("http://") || s.startsWith("https://")); }
    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** 괄호/브라켓/Featuring/특수기호 제거 + 소문자 + 공백 정리 */
    private String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);
        x = x.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");   // 괄호류 제거
        x = x.replaceAll("\\b(feat\\.|ft\\.|with)\\b.*", " ");     // featuring 뒷부분 제거
        x = x.replaceAll("[^0-9a-z가-힣ㄱ-ㅎㅏ-ㅣ\\s]", " ");      // 특수문자 제거
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }
}