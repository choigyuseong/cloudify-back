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
        // 1) 우리 AT 쿠키로 userId 복원 (없으면 userId = null)
        UUID userId = resolveUserIdFromCookie(req);

        // 2) 유저별 OAuthCredentials 조회
        Optional<OAuthCredentials> credsOpt =
                (userId != null) ? repo.findByUser_Id(userId) : Optional.empty();

        // 2-1) 자격증명 자체가 없음 → 사실상 최초 연결로 간주 → 동의 필요
        if (credsOpt.isEmpty()) {
            return true;
        }

        OAuthCredentials c = credsOpt.get();

        // 2-2) 우리가 이전에 revoke한 사용자라면 → 다시 권한 받도록 동의 필요
        if (c.isRevoked()) {
            return true;
        }

        // 2-3) refresh_token 이 아예 저장되어 있지 않으면
        //      (초기에 offline 동의 실패/정책오류/수동 초기화 등)
        if (isBlank(c.getRefreshTokenEnc())) {
            return true;
        }

        // 2-4) 스코프 부족 여부 확인
        //      DB에 저장된 scopes 문자열을 집합으로 변환 후,
        //      requiredScopes(예: GoogleScopes.REQUIRED) 를 모두 포함하는지 검사
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