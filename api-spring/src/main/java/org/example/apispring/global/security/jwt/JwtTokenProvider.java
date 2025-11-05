package org.example.apispring.global.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    public static final String TOKEN_TYPE = "type";
    public static final String TOKEN_TYPE_ACCESS  = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties props;   // record: issuer(), accessTokenExpiration(), ...
    private final Clock clock;
    private final SecretKey hmacKey;     // JwtConfig에서 Bean으로 생성/주입

    public String createAccessToken(UUID userId) {
        Instant now = Instant.now(clock);
        Instant exp = now.plusMillis(props.accessTokenExpiration());
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(userId.toString())
                .claim(TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(hmacKey, Jwts.SIG.HS256)
                .compact();
    }

    public String createRefreshToken(UUID userId) {
        Instant now = Instant.now(clock);
        Instant exp = now.plusMillis(props.refreshTokenExpiration());
        return Jwts.builder()
                .issuer(props.issuer())
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
                .requireIssuer(props.issuer())
                .clockSkewSeconds(props.allowedClockSkewSeconds())
                .verifyWith(hmacKey)
                .build();

        Jws<Claims> jws = parser.parseSignedClaims(token);
        Claims c = jws.getPayload();

        return new TokenClaims(
                UUID.fromString(c.getSubject()),
                c.get(TOKEN_TYPE, String.class),
                c.getId(),                                // AT면 null일 수 있음
                c.getExpiration().toInstant()
        );
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
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
