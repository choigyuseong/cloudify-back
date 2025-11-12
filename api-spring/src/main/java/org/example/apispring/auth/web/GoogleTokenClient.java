package org.example.apispring.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

//@Component
@RequiredArgsConstructor
public class GoogleTokenClient {
    private final RestTemplate rt = new RestTemplate();

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    String clientId;
    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    String clientSecret;

    public RefreshResult refresh(String refreshToken) {
        var url = "https://oauth2.googleapis.com/token";
        var body = new LinkedMultiValueMap<String, String>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);

        try {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            var resp = rt.postForEntity(url, new HttpEntity<>(body, headers), Map.class);

            var m = resp.getBody();
            String accessToken = (String) m.get("access_token");
            Integer expiresIn  = (Integer) m.get("expires_in");
            String scopeJoined = (String) m.get("scope");
            return RefreshResult.ok(accessToken, expiresIn != null ? expiresIn : 3600, scopeJoined);

        } catch (HttpClientErrorException.BadRequest e) {
            String err = "bad_request";
            try {
                var m = new ObjectMapper().readValue(e.getResponseBodyAsString(), Map.class);
                err = (String) m.getOrDefault("error", err); // invalid_grant 등
            } catch (Exception ignored) {}
            return RefreshResult.error(err);

        } catch (Exception e) {
            return RefreshResult.error("io_error");
        }
    }

    public record RefreshResult(boolean ok, String accessToken, long expiresInSec, String scopeJoined, String error) {
        public static RefreshResult ok(String at, long exp, String scope) { return new RefreshResult(true, at, exp, scope, null); }
        public static RefreshResult error(String err) { return new RefreshResult(false, null, 0, null, err); }
    }
}
