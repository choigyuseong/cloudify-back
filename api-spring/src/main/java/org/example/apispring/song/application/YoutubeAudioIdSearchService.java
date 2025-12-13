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
public class YoutubeAudioIdSearchService {

    private final YoutubeClient youtubeClient;

    @Value("${cloudify.youtube.candidatesPerSearch:8}")
    private int candidatesPerSearch;

    @Value("${cloudify.youtube.lyricsEarlyStopScore:0.90}")
    private double earlyStopScore;

    public String findAudioId(String title, String artist) {
        if (title == null || artist == null) return null;

        String query = (title + " " + artist + " lyrics").trim();
        if (query.isBlank()) return null;

        ResponseEntity<String> res = youtubeClient.search(query, candidatesPerSearch);
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) return null;

        JSONObject json = new JSONObject(res.getBody());
        if (json.has("error")) return null;

        JSONArray items = json.optJSONArray("items");
        if (items == null || items.isEmpty()) return null;

        return pickBestLyrics(items, title, artist);
    }

    private String pickBestLyrics(JSONArray items, String title, String artist) {
        String wantTitle = normalizeForSearch(title);
        List<String> wantArtists = splitArtists(artist);

        JSONObject bestItem = null;
        double bestScore = -999;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            JSONObject snippet = item.optJSONObject("snippet");
            if (snippet == null) continue;

            JSONObject idObj = item.optJSONObject("id");
            String vId = idObj != null ? idObj.optString("videoId", null) : null;
            if (vId == null || vId.isBlank()) continue;

            String vTitleNorm = normalizeForSearch(snippet.optString("title", ""));
            String chNameNorm = normalizeForSearch(snippet.optString("channelTitle", ""));

            String noisy = snippet.optString("title", "").toLowerCase(Locale.ROOT);

            double s = 0.0;

            for (String a : wantArtists) {
                if (matchArtists(chNameNorm, a) || vTitleNorm.contains(a)) s += 0.45;
                else if (chNameNorm.contains(a) || a.contains(chNameNorm)) s += 0.25;
            }

            if (matchTitles(vTitleNorm, wantTitle)) s += 0.25;
            else if (vTitleNorm.contains(wantTitle)) s += 0.15;

            boolean hasLyrics = noisy.contains("lyrics") || noisy.contains("lyric") || noisy.contains("가사");
            if (hasLyrics) s += 0.35;
            else s -= 0.15;

            if (noisy.matches(".*\\b(mv|music video)\\b.*")) s -= 0.60;
            if (noisy.matches(".*\\b(performance|dance|practice)\\b.*")) s -= 0.50;
            if (noisy.matches(".*\\b(live|fancam)\\b.*")) s -= 0.50;
            if (noisy.matches(".*\\b(teaser|trailer)\\b.*")) s -= 0.80;

            if (noisy.matches(".*\\b(cover|remix|nightcore|sped up|slowed|8d)\\b.*")) s -= 0.50;

            if (snippet.optString("channelTitle", "").toLowerCase(Locale.ROOT).endsWith("topic")) s += 0.15;

            if (s > bestScore) {
                bestScore = s;
                bestItem = item;
            }

            if (s >= earlyStopScore) break;
        }

        if (bestItem == null) return null;

        JSONObject idObj = bestItem.optJSONObject("id");
        return idObj != null ? idObj.optString("videoId", null) : null;
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
                .map(YoutubeAudioIdSearchService::normalizeForSearch)
                .toList();
    }

    private boolean matchTitles(String queryTitle, String targetTitle) {
        return normalizeForSearch(queryTitle).equals(normalizeForSearch(targetTitle));
    }

    private boolean matchArtists(String queryArtist, String targetArtist) {
        return normalizeForSearch(queryArtist).equals(normalizeForSearch(targetArtist));
    }
}
