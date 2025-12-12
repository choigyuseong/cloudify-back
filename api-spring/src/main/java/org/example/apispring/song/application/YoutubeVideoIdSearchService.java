package org.example.apispring.song.application;

import lombok.RequiredArgsConstructor;
import org.example.apispring.song.web.YoutubeClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class YoutubeVideoIdSearchService {

    private final YoutubeClient youtubeClient;

    @Value("${cloudify.youtube.candidatesPerSearch:8}")
    private int candidatesPerSearch;

    @Value("${cloudify.youtube.earlyStopScore:0.90}")
    private double earlyStopScore;

    private static final List<String> DOMESTIC_OFFICIAL_CHANNELS = Arrays.asList(
            "1thek", "원더케이", "stone music", "genie", "kakao", "loen",
            "bighit", "hybe", "smtown", "jyp", "yg", "starship"
    );

    public String findVideoId(String title, String artist) {
        if (title == null || artist == null) {
            return null;
        }

        String query = (title + " " + artist + " official music video").trim();
        if (query.isBlank()) {
            return null;
        }

        ResponseEntity<String> res = youtubeClient.search(query, candidatesPerSearch);
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
            return null;
        }

        JSONObject json = new JSONObject(res.getBody());
        if (json.has("error")) {
            return null;
        }

        JSONArray items = json.optJSONArray("items");
        if (items == null || items.isEmpty()) {
            return null;
        }

        return pickBest(items, title, artist);
    }

    private String pickBest(JSONArray items, String title, String artist) {
        String wantTitle = normalizeForSearch(title);
        List<String> wantArtists = splitArtists(artist);

        JSONObject bestItem = null;
        double bestScore = -999;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject snippet = item.getJSONObject("snippet");

            String vTitle = normalizeForSearch(snippet.optString("title", ""));
            String chName = normalizeForSearch(snippet.optString("channelTitle", ""));

            double s = 0.0;

            for (String a : wantArtists) {
                if (matchArtists(chName, a) || vTitle.contains(a)) s += 0.60;
                else if (chName.contains(a) || a.contains(chName)) s += 0.45;
            }

            if (matchTitles(vTitle, wantTitle)) s += 0.30;
            else if (vTitle.contains(wantTitle)) s += 0.20;

            String noisy = snippet.optString("title", "").toLowerCase(Locale.ROOT);

            boolean isOfficial = snippet.optString("channelTitle", "").toLowerCase(Locale.ROOT).contains("official")
                    || snippet.optString("channelTitle", "").toLowerCase(Locale.ROOT).contains("vevo")
                    || snippet.optString("channelTitle", "").toLowerCase(Locale.ROOT).endsWith("topic");

            for (String c : DOMESTIC_OFFICIAL_CHANNELS) {
                if (snippet.optString("channelTitle", "").toLowerCase(Locale.ROOT).contains(c.toLowerCase(Locale.ROOT))) {
                    s += 0.10;
                    isOfficial = true;
                }
            }

            if (noisy.matches(".*\\blive\\b.*")) {
                if (!isOfficial) s -= 0.40;
            }

            if (noisy.matches(".*\\b(cover|remix|nightcore|sped up|lyrics|fancam|practice|dance|performance)\\b.*")) {
                s -= 0.40;
            }

            if (snippet.optString("channelTitle", "").toLowerCase(Locale.ROOT).contains("official")
                    || snippet.optString("channelTitle", "").toLowerCase(Locale.ROOT).contains("vevo")) {
                s += 0.20;
            }
            if (noisy.contains("official") || noisy.contains("mv")) {
                s += 0.20;
            }

            if (s > bestScore) {
                bestScore = s;
                bestItem = item;
            }

            if (s >= earlyStopScore) break;
        }

        if (bestItem != null) {
            return bestItem.getJSONObject("id").optString("videoId", null);
        }

        return null;
    }

    private static String normalizeForSearch(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        x = x.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");
        x = x.replaceAll("\\b(feat\\.|ft\\.|with)\\b.*", " ");
        x = x.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣぁ-ゔァ-ヴー一-龯々〆〤\\s']", " ");
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }

    private List<String> splitArtists(String artist) {
        if (artist == null) return List.of();
        return Arrays.stream(artist.split("\\s*(?:&|/|,|and|feat\\.?|ft\\.?)\\s*"))
                .map(String::trim)
                .filter(a -> !a.isEmpty())
                .map(YoutubeVideoIdSearchService::normalizeForSearch)
                .toList();
    }

    private boolean matchTitles(String queryTitle, String targetTitle) {
        return normalizeForSearch(queryTitle).equals(normalizeForSearch(targetTitle));
    }

    private boolean matchArtists(String queryArtist, String targetArtist) {
        return normalizeForSearch(queryArtist).equals(normalizeForSearch(targetArtist));
    }
}
