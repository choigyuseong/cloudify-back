package org.example.apispring.recommend.application;

import io.micrometer.common.lang.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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

    public String fetchAlbumImage(String title, String artist) {
        return fetchAlbumImage(title, artist, null);
    }

    public String fetchAlbumImage(String title,
                                  String artist,
                                  @Nullable org.example.apispring.recommend.service.common.RequestBudget budget) {

        if (budget != null && !budget.takeOne()) {
            return null;
        }

        if (geniusToken == null || geniusToken.isBlank()) {
            log.warn("[Genius] token missing");
            throw new BusinessException(ErrorCode.GENIUS_API_TOKEN_MISSING);
        }

        try {
            String q = (isBlank(artist) ? title : (artist + " " + title));
            String url = "https://api.genius.com/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + geniusToken);

            ResponseEntity<String> res;
            try {
                res = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            } catch (Exception ex) {
                log.warn("[Genius] HTTP error: {} - {}", ex.getClass().getSimpleName(), ex.getMessage());
                throw new BusinessException(ErrorCode.GENIUS_UPSTREAM_ERROR, "Genius HTTP error: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            }

            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                log.warn("[Genius] non-2xx response: {}", res.getStatusCode());
                throw new BusinessException(ErrorCode.GENIUS_UPSTREAM_ERROR, "Genius API non-2xx status: " + res.getStatusCode());
            }

            JSONObject root;
            try {
                root = new JSONObject(res.getBody());
            } catch (Exception e) {
                log.warn("[Genius] invalid JSON structure: {}", e.toString());
                throw new BusinessException(
                        ErrorCode.GENIUS_RESPONSE_INVALID,
                        "Failed to parse Genius JSON: " + e.getMessage()
                );
            }

            JSONObject resp = root.optJSONObject("response");
            if (resp == null) {
                return null;
            }

            JSONArray hits = resp.optJSONArray("hits");
            if (hits == null || hits.isEmpty()) {
                return null;
            }

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
                if (art == null) continue;

                String primaryArtist = norm(primaryArtistName(result));
                String rTitle = norm(result.optString("title", ""));
                String fullTitle = norm(result.optString("full_title", ""));

                double s = 0.0;

                if (!wantArtist.isEmpty() && !primaryArtist.isEmpty()) {
                    if (primaryArtist.equals(wantArtist)) s += 0.60;
                    else if (primaryArtist.contains(wantArtist) || wantArtist.contains(primaryArtist)) s += 0.45;
                    else continue;
                }

                if (!wantTitle.isEmpty()) {
                    if (rTitle.equals(wantTitle)) s += 0.30;
                    else if (rTitle.contains(wantTitle) || fullTitle.contains(wantTitle)) s += 0.20;
                }

                String noisy = rTitle + " " + fullTitle;
                if (noisy.matches(".*\\b(live|cover|remix|nightcore|sped up|lyrics)\\b.*")) s -= 0.25;

                String uploader = norm(result.optJSONObject("primary_artist").optString("name", ""));
                if (uploader.contains("genius")) s += 0.15;

                if (s > bestScore) {
                    bestScore = s;
                    best = result;
                }
            }

            if (best != null) {
                String art = pickArtUrl(best);
                if (art != null) return art;
            }

            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.optJSONObject(i);
                if (hit == null) continue;
                JSONObject result = hit.optJSONObject("result");
                String art = pickArtUrl(result);
                if (art != null) return art;
            }

            return null;

        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("[Genius] unexpected error: {}", e.toString());
            throw new BusinessException(ErrorCode.GENIUS_UPSTREAM_ERROR, "Unexpected Genius error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

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
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean isHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);
        x = Normalizer.normalize(x, Normalizer.Form.NFKC);
        x = x.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");
        x = x.replaceAll("\\b(feat\\.|ft\\.|with)\\b.*", " ");
        x = x.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣぁ-ゔァ-ヴー一-龯々〆〤\\s']", " ");
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }
}
