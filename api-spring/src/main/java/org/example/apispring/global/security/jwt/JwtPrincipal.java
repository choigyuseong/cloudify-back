package org.example.apispring.global.security.jwt;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

public record JwtPrincipal(UUID userId) implements Principal {
    public JwtPrincipal {
        Objects.requireNonNull(userId, "userId");
    }
    @Override public String getName() { return userId.toString(); }
}