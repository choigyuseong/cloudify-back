package org.example.apispring.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.apispring.auth.application.AuthService;
import org.example.apispring.auth.application.OAuthCredentialService;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.global.security.jwt.CookieUtil;
import org.example.apispring.global.security.jwt.JwtPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CookieUtil cookies;

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest req, HttpServletResponse res) {
        String rt = cookies.readRefresh(req)
                .orElseThrow(() -> new BusinessException(ErrorCode.JWT_MISSING));

        var pair = authService.refresh(rt);

        cookies.writeAccess(res, pair.accessToken(), pair.accessTokenTtlSeconds());
        cookies.writeRefresh(res, pair.refreshToken(), pair.refreshTokenTtlSeconds());

        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal JwtPrincipal principal,
            HttpServletResponse res
    ) {
        if (principal != null) {
            authService.logout(principal.userId());
        }
        cookies.clearAccess(res);
        cookies.clearRefresh(res);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        if (auth == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        JwtPrincipal p = (JwtPrincipal) auth.getPrincipal();
        return Map.of("userId", p.userId());
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Void> disconnect(
            @AuthenticationPrincipal JwtPrincipal principal,
            HttpServletResponse res
    ) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        var userId = principal.userId();

        authService.disconnectAccount(userId);

        cookies.clearAccess(res);
        cookies.clearRefresh(res);

        return ResponseEntity.noContent().build();
    }
}
