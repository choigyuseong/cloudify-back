package org.example.apispring.global.security.jwt;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CookieUtil {
    public static final String ACCESS_COOKIE = "AT";
    public static final String REFRESH_COOKIE = "RT";

    @Value("${security.cookie.secure:true}")
    private boolean secure;

    @Value("${security.cookie.samesite:None}")
    private String sameSite;

    @Value("${security.cookie.domain:}")
    private String domain;

    private String build(String name, String val, int maxAge, String path) {
        StringBuilder sb = new StringBuilder()
                .append(name).append("=").append(val == null ? "" : val).append("; ")
                .append("Path=").append(path).append("; ")
                .append("HttpOnly; ");

        // ✅ Secure 옵션은 환경에 따라 추가
        if (secure) sb.append("Secure; ");

        sb.append("SameSite=").append(sameSite).append("; ")
                .append("Max-Age=").append(maxAge);

        if (domain != null && !domain.isBlank()) sb.append("; Domain=").append(domain);

        return sb.toString();
    }

    public void writeAccess(HttpServletResponse res, String token, long maxAgeSeconds) {
        res.addHeader("Set-Cookie", build(ACCESS_COOKIE, token, (int) maxAgeSeconds, "/"));
    }

    public void writeRefresh(HttpServletResponse res, String token, long maxAgeSeconds) {
        res.addHeader("Set-Cookie", build(REFRESH_COOKIE, token, (int) maxAgeSeconds, "/api/auth/refresh"));
    }

    public void clearAccess(HttpServletResponse res) {
        res.addHeader("Set-Cookie", build(ACCESS_COOKIE, "", 0, "/"));
    }

    public void clearRefresh(HttpServletResponse res) {
        res.addHeader("Set-Cookie", build(REFRESH_COOKIE, "", 0, "/api/auth/refresh"));
    }

    private Optional<String> readCookie(HttpServletRequest req, String name) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return Optional.empty();
        for (Cookie c : cs) {
            if (name.equals(c.getName())) {
                return Optional.ofNullable(c.getValue());
            }
        }
        return Optional.empty();
    }

    public Optional<String> readAccess(HttpServletRequest req) {
        return readCookie(req, ACCESS_COOKIE);
    }

    public Optional<String> readRefresh(HttpServletRequest req) {
        return readCookie(req, REFRESH_COOKIE);
    }
}
