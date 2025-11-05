package org.example.apispring.auth.infra;

import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;

@Component
public class GoogleTokenRevoker {
    private static final URI REVOCATION_URI = URI.create("https://oauth2.googleapis.com/revoke");

    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return; // 멱등
        try {
            var body = "token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
            var req = HttpRequest.newBuilder(REVOCATION_URI)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // 로깅 정도만(실패해도 멱등, 다음에 다시 revoke 시도 가능)
        }
    }
}