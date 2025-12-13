package org.example.apispring.global.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.apispring.global.error.BusinessException;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ACCESS_TOKEN_COOKIE = "AT";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwt;
    private final JwtErrorCodeMapper jwtErrorCodeMapper;
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        if (HttpMethod.OPTIONS.matches(req.getMethod())) return true; // CORS preflight
        return
                p.equals("/") ||
                        p.startsWith("/error") ||
                        p.startsWith("/actuator/health") ||
                        p.startsWith("/v3/api-docs") ||
                        p.startsWith("/swagger-ui") ||
                        p.startsWith("/oauth2/") ||
                        p.startsWith("/login/oauth2/") ||
                        p.startsWith("/api/auth/refresh") ||
                        p.startsWith("/api/auth/logout");
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
            var claims = jwt.decodeAccess(token);

            if (claims.userId() == null) {
                throw new IllegalArgumentException("JWT missing userId");
            }

            var principal = new JwtPrincipal(claims.userId());
            var auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));

            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);

        } catch (Exception e) {
            SecurityContextHolder.clearContext();

            var ec = jwtErrorCodeMapper.map(e);
            handlerExceptionResolver.resolveException(req, res, null, new BusinessException(ec));
            return;
        }
    }

    private String resolveAccessToken(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (ACCESS_TOKEN_COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        String h = req.getHeader(AUTH_HEADER);
        if (h != null && h.startsWith(BEARER_PREFIX)) {
            return h.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
