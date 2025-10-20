package org.example.apispring.global.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    private String secret;
    private String issuer = "cloudify-api";
    private long accessTokenExpiration = 15 * 60 * 1000L;
    private long refreshTokenExpiration = 14L * 24 * 60 * 60 * 1000L;
    private long allowedClockSkewSeconds = 30L;
}
