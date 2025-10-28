package org.example.apispring.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.apispring.global.security.jwt.CookieUtil;
import org.example.apispring.global.security.jwt.JwtPrincipal;
import org.example.apispring.global.security.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final JwtTokenProvider jwt;
    private final CookieUtil cookie;

    @Value("${jwt.access-token-expiration}")
    private long atExpMillis;
    @Value("${jwt.refresh-token-expiration}")
    private long rtExpMillis;

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest req, HttpServletResponse res) {
        String rt = cookie.readRefresh(req).orElseThrow(() ->
                new InsufficientAuthenticationException("NO_REFRESH_TOKEN"));

        var tc = jwt.decodeRefresh(rt); // typ=refresh, jti 존재, exp 검사 포함
        UUID uid = tc.userId();

        // (선택) Redis 등으로 jti 재사용 방지 로직 추가 (ensureNotReused(tc.jti()))

        String newAt = jwt.createAccessToken(uid);
        String newRt = jwt.createRefreshToken(uid); // jti 갱신

        cookie.writeAccess(res, newAt, atExpMillis / 1000);
        cookie.writeRefresh(res, newRt, rtExpMillis / 1000);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse res) {
        // (선택) 서버 측 RT jti 블랙리스트 처리
        cookie.clearAccess(res);
        cookie.clearRefresh(res);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        if (auth == null) throw new InsufficientAuthenticationException("NO_AUTH");
        JwtPrincipal p = (JwtPrincipal) auth.getPrincipal();
        return Map.of("userId", p.userId());
    }
}
