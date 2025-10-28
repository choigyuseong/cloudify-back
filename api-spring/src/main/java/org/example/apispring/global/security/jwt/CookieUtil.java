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

    @Value("${cookie.secure:true}")     private boolean secure;
    @Value("${cookie.same-site:None}")  private String sameSite; // None/Lax/Strict
    @Value("${cookie.domain:}")         private String domain;   // 필요 시만 지정(빈 문자열이면 미지정)

    private String build(String name, String val, int maxAge, String path) {
        StringBuilder sb = new StringBuilder()
                .append(name).append("=").append(val == null ? "" : val).append("; ")
                .append("Path=").append(path).append("; ")
                .append("HttpOnly; ")
                .append("Secure; ")
                .append("SameSite=").append(sameSite).append("; ")
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

    public Optional<String> readRefresh(HttpServletRequest req) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return Optional.empty();
        for (Cookie c : cs) if (REFRESH_COOKIE.equals(c.getName())) return Optional.ofNullable(c.getValue());
        return Optional.empty();
    }
}
