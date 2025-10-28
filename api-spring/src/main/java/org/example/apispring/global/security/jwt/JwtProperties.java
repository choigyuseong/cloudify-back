package org.example.apispring.global.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        String issuer,
        long accessTokenExpiration,
        long refreshTokenExpiration,
        long allowedClockSkewSeconds
) {}
