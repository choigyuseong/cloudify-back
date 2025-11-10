package org.example.apispring.global.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.apispring.auth.application.GoogleConsentStrategy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class GoogleOAuth2RequestResolver implements OAuth2AuthorizationRequestResolver {

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final GoogleConsentStrategy consentStrategy;

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(request, delegate(request).resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(request, delegate(request).resolve(request, clientRegistrationId));
    }

    private DefaultOAuth2AuthorizationRequestResolver delegate(HttpServletRequest request) {
        return new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization");
    }

    private OAuth2AuthorizationRequest customize(HttpServletRequest req, OAuth2AuthorizationRequest original) {
        if (original == null) return null;

        Set<String> requested = original.getScopes() != null
                ? new LinkedHashSet<>(original.getScopes()) : Collections.emptySet();

        Map<String, Object> add = new HashMap<>(original.getAdditionalParameters());
        add.put("include_granted_scopes", "true");
        add.put("access_type", "offline");

        if (consentStrategy.needPrompt(req, requested)) {
            add.put("prompt", "consent");
        }

        return OAuth2AuthorizationRequest.from(original)
                .additionalParameters(add)
                .build();
    }
}
