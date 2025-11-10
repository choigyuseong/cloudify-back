package org.example.apispring.global.security.jwt;

import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public SecretKey jwtHmacKey(JwtProperties props) {
        byte[] keyBytes = Objects.requireNonNull(props.secret(), "jwt.secret must not be null")
                .getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("jwt.secret must be >= 32 bytes");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
