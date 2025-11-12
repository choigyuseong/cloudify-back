package org.example.apispring.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.apispring.auth.application.AuthService;
import org.example.apispring.auth.application.OAuthCredentialService;
import org.example.apispring.global.security.jwt.CookieUtil;
import org.example.apispring.global.security.jwt.JwtTokenProvider;
import org.example.apispring.global.security.jwt.RefreshTokenJtiStore;
import org.example.apispring.user.application.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final OAuthCredentialService credentialService;
    private final OAuth2AuthorizedClientService clientService;
    private final AuthService authService;
    private final CookieUtil cookies;

    @Value("${app.front.redirect-url}")
    private String frontRedirectUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication auth)
            throws IOException {
        // 구글로부터 프로필 추출해서 유저 upsert
        var oUser = (OAuth2User) auth.getPrincipal();
        String sub = oUser.getAttribute("sub");
        String email = oUser.getAttribute("email");
        String name = oUser.getAttribute("name");
        String pictureUrl = oUser.getAttribute("picture");

        var user = userService.upsertByGoogle(sub, email, name, pictureUrl);

        // 누가 어떤 클라이언트로 인증했는가를 담은 인증 토큰 , 이걸 통해서 만들어지는 client 에 AT / RT 가 담김
        var oauthToken = (OAuth2AuthenticationToken) auth;

        String regId = oauthToken.getAuthorizedClientRegistrationId();
        String principalName = oauthToken.getName();
        OAuth2AuthorizedClient client = clientService.loadAuthorizedClient(regId, principalName);

        if (client != null) {
            var gAt = client.getAccessToken();
            var gRt = client.getRefreshToken();
            var scopes = gAt.getScopes();
            var exp = gAt.getExpiresAt();

            try {
                credentialService.saveOrUpdate(
                        user.getId(),
                        gAt.getTokenValue(),
                        gRt != null
                                ? gRt.getTokenValue()
                                : null,
                        exp,
                        scopes
                );
            } catch (IllegalStateException ex) {
                String consentUrl = req.getContextPath()
                        + "/oauth2/authorization/google?prompt=consent&access_type=offline";
                res.sendRedirect(consentUrl);
                return;
            }
        }

        var tokens = authService.issueTokens(user.getId());
        cookies.writeAccess(res, tokens.accessToken(), tokens.accessTokenTtlSeconds());
        cookies.writeRefresh(res, tokens.refreshToken(), tokens.refreshTokenTtlSeconds());

        res.sendRedirect(frontRedirectUrl);
    }
}