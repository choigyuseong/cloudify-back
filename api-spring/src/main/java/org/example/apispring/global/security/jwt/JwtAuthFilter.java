package org.example.apispring.global.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ACCESS_TOKEN_COOKIE = "AT";

    private final JwtTokenProvider jwt;
    private final AuthenticationEntryPoint authEntryPoint;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/oauth2/") ||
                path.startsWith("/login/oauth2/") ||
                path.startsWith("/error") ||
                path.startsWith("/health") ||
                path.equals("/") ||
                path.startsWith("/api/auth/refresh"); // 리프레시는 별도 엔드포인트에서 처리
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String token = resolveAccessToken(req);
        if (token == null || token.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        try {
            var claims = jwt.decodeAccess(token); // 검증 + 클레임 파싱
            JwtPrincipal principal = new JwtPrincipal(claims.userId());

            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);
        } catch (Exception e) {
            // TODO: 여기서 에러코드 매핑(예: 만료/위조/형식오류 → UNAUTHORIZED 코드) 후 JSON 표준화
            SecurityContextHolder.clearContext();
            authEntryPoint.commence(req, res, new InsufficientAuthenticationException("Invalid JWT", e));
        }
    }

    private String resolveAccessToken(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (ACCESS_TOKEN_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
