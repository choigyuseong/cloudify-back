package org.example.apispring.global.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GoogleOAuth2RequestResolver implements OAuth2AuthorizationRequestResolver {

    private final ClientRegistrationRepository crRepo;

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest req) {
        return customize(delegate(req).resolve(req));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest req, String clientRegistrationId) {
        return customize(delegate(req).resolve(req, clientRegistrationId));
    }

    private DefaultOAuth2AuthorizationRequestResolver delegate(HttpServletRequest request) {
        return new DefaultOAuth2AuthorizationRequestResolver(crRepo, "/oauth2/authorization");
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest req) {
        if (req == null) return null;
        Map<String, Object> add = new HashMap<>(req.getAdditionalParameters());
        add.put("access_type", "offline");
        add.put("include_granted_scopes", "true");
        add.put("prompt", "consent");

        return OAuth2AuthorizationRequest.from(req)
                .additionalParameters(add)
                .build();
    }
}