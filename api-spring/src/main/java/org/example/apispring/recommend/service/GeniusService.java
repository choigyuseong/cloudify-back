package org.example.apispring.recommend.service;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

@Slf4j
@Service
public class GeniusService {

    private final RestTemplate http = new RestTemplate();

    @Value("${genius.api.token}")
    private String geniusToken;

    /**
     * 기존 호출과 호환용(예산 가드 없이 호출)
     */
    public String fetchAlbumImage(String title, String artist) {
        return fetchAlbumImage(title, artist, null);
    }

    /**
     * 예산 가드 포함 버전
     */
    public String fetchAlbumImage(String title, String artist, org.example.apispring.recommend.service.common.RequestBudget budget) {
        try {
            if (budget != null && !budget.takeOne()) return null;

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
                if (art == null || isDefaultGeniusImage(art)) continue; // 로고/기본 이미지 제외

                String primaryArtist = norm(primaryArtistName(result));
                String rTitle = norm(result.optString("title", ""));
                String fullTitle = norm(result.optString("full_title", ""));

                double s = 0.0;

                // 1) 아티스트 정합성
                if (!wantArtist.isEmpty() && !primaryArtist.isEmpty()) {
                    if (primaryArtist.equals(wantArtist)) s += 0.60;
                    else if (primaryArtist.contains(wantArtist) || wantArtist.contains(primaryArtist)) s += 0.45;
                    else continue; // 아티스트가 너무 다르면 skip
                }

                // 2) 곡 제목 정합성
                if (!wantTitle.isEmpty()) {
                    if (rTitle.equals(wantTitle)) s += 0.30;
                    else if (rTitle.contains(wantTitle) || fullTitle.contains(wantTitle)) s += 0.20;
                }

                // 3) 잡음 패널티
                String noisy = rTitle + " " + fullTitle;
                if (noisy.matches(".*\\b(live|cover|remix|nightcore|sped up|lyrics)\\b.*")) s -= 0.25;

                // 4) [Genius] 업로드 아티스트 보너스
                String uploader = norm(result.optJSONObject("primary_artist").optString("name", ""));
                if (uploader.contains("genius")) s += 0.15;

                if (s > bestScore) {
                    bestScore = s;
                    best = result;
                }
            }

            // best 후보가 있으면 반환
            if (best != null) {
                String art = pickArtUrl(best);
                if (art != null && !isDefaultGeniusImage(art)) return art;
            }

            // 최후 fallback: 최소 점수 threshold 이상인 후보만
            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.optJSONObject(i);
                if (hit == null) continue;
                JSONObject result = hit.optJSONObject("result");
                if (result == null) continue;

                String art = pickArtUrl(result);
                String primaryArtist = norm(primaryArtistName(result));
                String rTitle = norm(result.optString("title", ""));
                String fullTitle = norm(result.optString("full_title", ""));

                double s = 0.0;
                if (!wantArtist.isEmpty() && !primaryArtist.isEmpty()) {
                    if (primaryArtist.equals(wantArtist)) s += 0.60;
                    else if (primaryArtist.contains(wantArtist) || wantArtist.contains(primaryArtist)) s += 0.45;
                }
                if (!wantTitle.isEmpty()) {
                    if (rTitle.equals(wantTitle)) s += 0.30;
                    else if (rTitle.contains(wantTitle) || fullTitle.contains(wantTitle)) s += 0.20;
                }

                if (s >= 0.2 && art != null && !isDefaultGeniusImage(art)) { // 최소 매칭 점수 0.2 이상
                    return art;
                }
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
        String[] keys = new String[]{"song_art_image_url", "song_art_image_thumbnail_url", "header_image_url"};
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

    /**
     * 정규화 (지니어스+유튜브 개선)
     * - 괄호/Featuring 제거
     * - 특수문자 제거
     * - 소문자
     * - 공백 정리
     * - 일본어, 성조, 아포스트로피 포함 허용
     */
    private String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);
        x = Normalizer.normalize(x, Normalizer.Form.NFKC);
        x = x.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");
        x = x.replaceAll("\\b(feat\\.|ft\\.|with)\\b.*", " ");
        x = x.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣぁ-ゔァ-ヴー一-龯々〆〤\\s']", " "); // 아포스트로피 허용
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }

    /**
     * Genius 로고/기본 이미지 URL 패턴 확인
     */
    private boolean isDefaultGeniusImage(String url) {
        if (url == null) return true;
        return url.contains("/images/default") || url.contains("/default_thumb");
    }
}
