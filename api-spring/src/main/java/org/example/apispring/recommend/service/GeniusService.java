package org.example.apispring.recommend.service;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class GeniusService {

    private final RestTemplate http = new RestTemplate();

    @Value("${genius.api.key}")
    private String geniusToken;

    public String fetchAlbumImage(String title, String artist) {
        try {
            String query = title + " " + artist;
            String url = "https://api.genius.com/search?q=" + query.replace(" ", "%20");

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + geniusToken);

            ResponseEntity<String> res = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) return null;

            JSONObject root = new JSONObject(res.getBody());
            var hits = root.getJSONObject("response").getJSONArray("hits");
            if (hits.isEmpty()) return null;

            JSONObject song = hits.getJSONObject(0).getJSONObject("result");
            return song.optString("song_art_image_url", null);
        } catch (Exception e) {
            return null;
        }
    }
}
