package org.example.apispring.global.security.jwt;

import java.time.Instant;
import java.util.UUID;

public record TokenClaims(
        UUID userId,
        String tokenType,
        String jti,
        Instant exp
) {}
