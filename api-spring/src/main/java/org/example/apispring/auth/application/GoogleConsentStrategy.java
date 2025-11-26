package org.example.apispring.auth.application;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.apispring.auth.domain.OAuthCredentials;
import org.example.apispring.auth.domain.OAuthCredentialsRepository;
import org.example.apispring.global.security.jwt.CookieUtil;
import org.example.apispring.global.security.jwt.JwtTokenProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class GoogleConsentStrategy {

    private final CookieUtil cookies;
    private final JwtTokenProvider jwt;
    private final OAuthCredentialsRepository repo;

    public boolean needPrompt(HttpServletRequest req, Set<String> requiredScopes) {
        UUID userId = resolveUserIdFromCookie(req);

        Optional<OAuthCredentials> credsOpt =
                (userId != null)
                        ? repo.findByUser_Id(userId)
                        : Optional.empty();

        // 1) 최초 연결의 경우
        if (credsOpt.isEmpty()) {
            return true;
        }

        OAuthCredentials c = credsOpt.get();

        // 2) 우리가 revoke 한 사용자의 경우
        if (c.isRevoked()) {
            return true;
        }

        // 3) RT 가 존재하지 않는 경우
        if (isBlank(c.getRefreshTokenEnc())) {
            return true;
        }

        // 4) 스코프 추가 동의가 필요한 경우
        Set<String> ownedScopes = splitScopes(c.getScopes());
        if (!ownedScopes.containsAll(requiredScopes)) {
            return true;
        }

        return false;
    }

    private UUID resolveUserIdFromCookie(HttpServletRequest req) {
        try {
            var atOpt = cookies.readAccess(req);
            if (atOpt.isEmpty()) return null;
            var claims = jwt.decodeAccess(atOpt.get());
            return claims.userId();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Set<String> splitScopes(String raw) {
        if (isBlank(raw)) return Collections.emptySet();
        return Arrays.stream(raw.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}