package org.example.apispring.auth.web;

import lombok.RequiredArgsConstructor;
import org.example.apispring.global.security.jwt.JwtPrincipal;
import org.example.apispring.global.security.jwt.JwtProperties;
import org.example.apispring.global.security.jwt.JwtTokenProvider;
import org.example.apispring.user.domain.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtTokenProvider jwt;
    private final StringRedisTemplate redis;
    private final UserRepository userRepo;
    private final JwtProperties jwtProperties;

    private static final String AT = "AT";
    private static final String RT = "RT";

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest req, HttpServletResponse res) {
        String rt = getCookie(req, RT);
        if (rt == null || rt.isBlank()) {
            return ResponseEntity.status(401).build(); // TODO: 표준 에러 바디
        }

        var claims = jwt.decodeRefresh(rt); // TODO: 만료/위조 예외 → 401 매핑
        UUID userId = claims.userId();
        String jti = claims.jti();

        String key = "rtj:" + userId;
        String curr = redis.opsForValue().get(key);
        if (curr == null) {
            // 상태 없음(만료/로그아웃 이후) → 재로그인 요구
            return ResponseEntity.status(401).build(); // TODO
        }
        if (!jti.equals(curr)) {
            // 재사용 감지
            redis.delete(key); // 방어적으로 세션 제거
            clearCookie(res, AT);
            clearCookie(res, RT);
            return ResponseEntity.status(401).build(); // TODO: 재인증 유도 코드
        }

        // 새 토큰 발급
        String newAT = jwt.createAccessToken(userId);
        String newRT = jwt.createRefreshToken(userId);
        var newRTc = jwt.decodeRefresh(newRT);

        // Redis에 jti 교체(원자적 CAS가 이상적이지만 단일 세션 가정으로 단순화. 나중에 Lua로 개선)
        long ttlSec = newRTc.exp().getEpochSecond() - Instant.now().getEpochSecond();
        redis.opsForValue().set(key, newRTc.jti(), ttlSec, TimeUnit.SECONDS);

        setCookie(res, AT, newAT, (int)(jwtProperties.getAccessTokenExpiration()/1000));
        setCookie(res, RT, newRT, (int)(jwtProperties.getRefreshTokenExpiration()/1000));

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = RT, required = false) String rt,
                                       HttpServletResponse res) {
        if (rt != null) {
            try {
                var claims = jwt.decodeRefresh(rt);
                redis.delete("rtj:" + claims.userId());
            } catch (Exception ignored) {
                // TODO: 로깅만
            }
        }
        clearCookie(res, AT);
        clearCookie(res, RT);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@org.springframework.security.core.annotation.AuthenticationPrincipal JwtPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return userRepo.findById(principal.userId())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(Map.of(
                        "id", u.getId(),
                        "email", u.getEmail(),
                        "name", u.getName(),
                        "pictureUrl", u.getPictureUrl()
                )))
                .orElseGet(() -> ResponseEntity.status(404).build());
    }

    private String getCookie(HttpServletRequest req, String name) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return null;
        for (Cookie c : cs) if (name.equals(c.getName())) return c.getValue();
        return null;
    }

    private void setCookie(HttpServletResponse res, String name, String value, int maxAgeSec) {
        res.setHeader("Set-Cookie",
                ResponseCookie.from(name, value)
                        .httpOnly(true)
                        .secure(true)
                        .sameSite("None")
                        .path("/")
                        .maxAge(Duration.ofSeconds(maxAgeSec))
                        .build().toString());
    }

    private void clearCookie(HttpServletResponse res, String name) {
        res.setHeader("Set-Cookie",
                ResponseCookie.from(name, "")
                        .httpOnly(true)
                        .secure(true)
                        .sameSite("None")
                        .path("/")
                        .maxAge(Duration.ZERO)
                        .build().toString());
    }
}
