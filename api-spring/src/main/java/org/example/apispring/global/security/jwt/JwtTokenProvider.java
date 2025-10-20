package org.example.apispring.global.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    public static final String TOKEN_TYPE = "type";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties props;
    private final Clock clock;
    private SecretKey hmacKey;

    public record TokenClaims(UUID userId, String tokenType, String jti, Instant exp) {}

    @PostConstruct
    public void init() {
        byte[] keyBytes = Objects.requireNonNull(props.getSecret(), "jwt.secret must not be null")
                .getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("jwt.secret must be >= 32 bytes");
        }
        this.hmacKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String createAccessToken(UUID userId) {
        Instant now = Instant.now(clock);
        Instant exp = now.plusMillis(props.getAccessTokenExpiration());
        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(userId.toString())
                .claim(TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(hmacKey, Jwts.SIG.HS256)
                .compact();
    }

    public String createRefreshToken(UUID userId) {
        Instant now = Instant.now(clock);
        Instant exp = now.plusMillis(props.getRefreshTokenExpiration());
        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(userId.toString())
                .claim(TOKEN_TYPE, TOKEN_TYPE_REFRESH)
                .id(UUID.randomUUID().toString()) // jti
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(hmacKey, Jwts.SIG.HS256)
                .compact();
    }

    public TokenClaims decode(String token) {
        var parser = Jwts.parser()
                .requireIssuer(props.getIssuer())
                .clockSkewSeconds(props.getAllowedClockSkewSeconds())
                .verifyWith(hmacKey)
                .build();

        Jws<Claims> jws = parser.parseSignedClaims(token);
        Claims c = jws.getPayload();

        UUID userId = UUID.fromString(c.getSubject());
        String tokenType = c.get(TOKEN_TYPE, String.class);
        String jti = c.getId();
        Instant exp = c.getExpiration().toInstant();

        return new TokenClaims(userId, tokenType, jti, exp);
    }

    public TokenClaims decodeAccess(String token) {
        var tc = decode(token);
        if (!TOKEN_TYPE_ACCESS.equals(tc.tokenType())) {
            throw new MalformedJwtException("Not an access token");
        }
        return tc;
    }

    public TokenClaims decodeRefresh(String token) {
        var tc = decode(token);
        if (!TOKEN_TYPE_REFRESH.equals(tc.tokenType())) {
            throw new MalformedJwtException("Not a refresh token");
        }
        if (tc.jti() == null || tc.jti().isBlank()) {
            throw new MalformedJwtException("Refresh token missing jti");
        }
        return tc;
    }

    public boolean isValid(String token) {
        try {
            decode(token);
            return true;
        } catch (ExpiredJwtException | SecurityException | MalformedJwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
