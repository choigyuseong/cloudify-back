package org.example.apispring.global.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.apispring.auth.application.OAuthCredentialService;
import org.example.apispring.global.security.jwt.JwtProperties;
import org.example.apispring.global.security.jwt.JwtTokenProvider;
import org.example.apispring.user.application.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final UserService userService;
    private final OAuthCredentialService credentialService;
    private final JwtTokenProvider jwt;
    private final JwtProperties jwtProperties;
    private final StringRedisTemplate redis;

    @Value("${security.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${security.cookie.samesite:None}")
    private String cookieSameSite;

    private static final String AT = "AT";
    private static final String RT = "RT";

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        OidcUser oidc = (OidcUser) token.getPrincipal();

        String sub = oidc.getSubject();
        String email = (String) oidc.getClaims().get("email");
        String name = (String) oidc.getClaims().get("name");
        String picture = (String) oidc.getClaims().get("picture");
        // TODO: 필수 클레임 검증 실패 시 비즈니스 예외 매핑

        var user = userService.upsertBySub(sub, email, name, picture);

        OAuth2AuthorizedClient client =
                authorizedClientService.loadAuthorizedClient(token.getAuthorizedClientRegistrationId(), token.getName());
        // TODO: client null/AT null 방어

        var at = client.getAccessToken();
        var rt = client.getRefreshToken(); // 최초 동의가 아니면 null일 수 있음(정상)
        String scopes = String.join(" ", at.getScopes());
        Instant atExp = at.getExpiresAt();

        credentialService.store(
                user.getId(),
                at.getTokenValue(),
                rt != null ? rt.getTokenValue() : null,
                atExp,
                scopes
        );

        // 우리 서비스 JWT 발급 + 쿠키 세팅
        String accessJwt = jwt.createAccessToken(user.getId());
        String refreshJwt = jwt.createRefreshToken(user.getId());

        // Redis에 현재 RT jti 보관(단일 세션 가정)
        var refreshClaims = jwt.decodeRefresh(refreshJwt);
        String key = "rtj:" + user.getId();
        long ttlSec = (refreshClaims.exp().getEpochSecond() - Instant.now().getEpochSecond());
        redis.opsForValue().set(key, refreshClaims.jti(), ttlSec, TimeUnit.SECONDS);

        addCookie(response, AT, accessJwt, (int)(jwtProperties.getAccessTokenExpiration()/1000));
        addCookie(response, RT, refreshJwt, (int)(jwtProperties.getRefreshTokenExpiration()/1000));

        try {
            response.sendRedirect("/"); // TODO: 프론트 첫 화면 경로에 맞게 조정
        } catch (IOException e) {
            throw new RuntimeException(e); // TODO: 비즈니스 예외 변환
        }
    }

    private void addCookie(HttpServletResponse res, String name, String value, int maxAgeSec, String path) {
        ResponseCookie rc = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)               // env
                .sameSite(cookieSameSite)           // env
                .path(path)
                .maxAge(Duration.ofSeconds(maxAgeSec))
                .build();

        res.addHeader("Set-Cookie", rc.toString()); // ★ addHeader 로 누적
    }
}
