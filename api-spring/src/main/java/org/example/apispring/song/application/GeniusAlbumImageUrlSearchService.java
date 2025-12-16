package org.example.apispring.song.application;

import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

@Slf4j
@Service
public class GeniusAlbumImageUrlSearchService {

    private static final double MIN_CONFIDENCE_SCORE = 0.20;

    public GeniusAlbumImageSearchResult extractAlbumImageUrl(
            ResponseEntity<String> res,
            String title,
            String artist,
            String rid,
            String songId
    ) {
        if (res == null || res.getBody() == null) {
            throw new BusinessException(ErrorCode.GENIUS_RESPONSE_INVALID, "songId=" + songId + " null_body");
        }

        final JSONObject root;
        try {
            root = new JSONObject(res.getBody());
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.GENIUS_RESPONSE_INVALID,
                    "songId=" + songId + " json_parse_fail ex=" + e.getClass().getSimpleName() + ":" + e.getMessage()
            );
        }

        JSONObject response = root.optJSONObject("response");
        if (response == null) {
            throw new BusinessException(ErrorCode.GENIUS_RESPONSE_INVALID, "songId=" + songId + " missing_response_node");
        }

        JSONArray hits = response.optJSONArray("hits");
        if (hits == null) {
            throw new BusinessException(ErrorCode.GENIUS_RESPONSE_INVALID, "songId=" + songId + " missing_hits");
        }

        if (hits.isEmpty()) {
            return GeniusAlbumImageSearchResult.noImage(-999.0, "NO_HITS");
        }

        Pick pick = selectBestHit(hits, title, artist);
        if (pick == null || pick.result == null) {
            throw new BusinessException(ErrorCode.GENIUS_RESPONSE_INVALID, "songId=" + songId + " no_result_in_hits");
        }

        if (pick.score < MIN_CONFIDENCE_SCORE) {
            throw new BusinessException(
                    ErrorCode.GENIUS_RESPONSE_INVALID,
                    "songId=" + songId + " low_confidence bestScore=" + pick.score
            );
        }

        ImageDecision img = decideImageUrl(pick.result);
        if (img.found) {
            return GeniusAlbumImageSearchResult.found(img.url, pick.score, "FOUND");
        }
        return GeniusAlbumImageSearchResult.noImage(pick.score, img.reason);
    }

    private Pick selectBestHit(JSONArray hits, String title, String artist) {
        String wantTitle = norm(title);
        String wantArtist = norm(artist);

        JSONObject best = null;
        double bestScore = -999.0;

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;

            JSONObject result = hit.optJSONObject("result");
            if (result == null) continue;

            String primaryArtist = norm(primaryArtistName(result));
            String rTitle = norm(result.optString("title", ""));
            String fullTitle = norm(result.optString("full_title", ""));

            double s = 0.0;

            if (!wantArtist.isEmpty() && !primaryArtist.isEmpty()) {
                if (primaryArtist.equals(wantArtist)) s += 0.60;
                else if (primaryArtist.contains(wantArtist) || wantArtist.contains(primaryArtist)) s += 0.45;
                else s -= 0.20;
            }

            if (!wantTitle.isEmpty()) {
                if (rTitle.equals(wantTitle)) s += 0.30;
                else if (rTitle.contains(wantTitle) || fullTitle.contains(wantTitle)) s += 0.20;
            }

            String noisy = (rTitle + " " + fullTitle);
            if (noisy.matches(".*\\b(live|cover|remix|nightcore|sped up|lyrics)\\b.*")) s -= 0.25;

            if (s > bestScore) {
                bestScore = s;
                best = result;
            }
        }

        if (best == null) return null;
        return new Pick(best, bestScore);
    }

    private ImageDecision decideImageUrl(JSONObject result) {
        // 이 3개 키 중 하나라도 존재하면 “이미지가 없다/있다”를 판단 가능하다고 봄.
        String[] keys = {"song_art_image_url", "song_art_image_thumbnail_url", "header_image_url"};

        boolean sawAnyKey = false;
        boolean sawNullOrDefault = false;

        for (String k : keys) {
            if (!result.has(k)) continue;
            sawAnyKey = true;

            Object raw = result.opt(k);
            if (raw == null || raw == JSONObject.NULL) {
                sawNullOrDefault = true;
                continue;
            }

            String v = String.valueOf(raw).trim();
            if (v.isBlank() || "null".equalsIgnoreCase(v)) {
                sawNullOrDefault = true;
                continue;
            }

            if (isDefaultGeniusImage(v)) {
                sawNullOrDefault = true;
                continue;
            }

            if (isHttp(v)) {
                return new ImageDecision(true, v, "OK");
            }
        }

        if (!sawAnyKey) {
            throw new BusinessException(ErrorCode.GENIUS_RESPONSE_INVALID, "missing_image_fields");
        }

        // 정상 응답 + 이미지가 없거나(default 포함) unusable → 이 케이스만 trash 허용
        if (sawNullOrDefault) {
            return new ImageDecision(false, null, "NO_IMAGE_URL");
        }

        // 키는 있었는데 usable한 URL이 하나도 없으면 구조/값 이상
        throw new BusinessException(ErrorCode.GENIUS_RESPONSE_INVALID, "image_fields_present_but_unusable");
    }

    private String primaryArtistName(JSONObject result) {
        JSONObject pa = result.optJSONObject("primary_artist");
        if (pa == null) return "";
        Object raw = pa.opt("name");
        if (raw == null || raw == JSONObject.NULL) return "";
        String name = String.valueOf(raw);
        return "null".equalsIgnoreCase(name) ? "" : name;
    }

    private boolean isDefaultGeniusImage(String url) {
        if (url == null) return true;
        return url.contains("/images/default") || url.contains("/default_thumb");
    }

    private boolean isHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
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

    private record Pick(JSONObject result, double score) {}

    private record ImageDecision(boolean found, String url, String reason) {}

    public record GeniusAlbumImageSearchResult(boolean found, String url, double bestScore, String reason) {
        public static GeniusAlbumImageSearchResult found(String url, double score, String reason) {
            return new GeniusAlbumImageSearchResult(true, url, score, reason);
        }
        public static GeniusAlbumImageSearchResult noImage(double score, String reason) {
            return new GeniusAlbumImageSearchResult(false, null, score, reason);
        }
    }
}
