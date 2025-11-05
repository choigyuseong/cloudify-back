package org.example.apispring.auth.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class ScopeGuard {

    private final OAuthCredentialService creds;

    public void requireScopes(UUID userId, Set<String> required) {
        if (required == null || required.isEmpty()) return;
        boolean ok = creds.hasAllScopes(userId, required);
        if (!ok) throw new MissingScopesException(required);
    }

    public static class MissingScopesException extends RuntimeException {
        private final Set<String> required;
        public MissingScopesException(Set<String> required) {
            super("Missing Google scopes: " + String.join(" ", required));
            this.required = required;
        }
        public Set<String> getRequired() { return required; }
    }
}