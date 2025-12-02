package org.example.apispring.auth.application;

import lombok.RequiredArgsConstructor;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.global.security.jwt.JwtProperties;
import org.example.apispring.global.security.jwt.JwtTokenProvider;
import org.example.apispring.global.security.jwt.RefreshTokenJtiStore;
import org.example.apispring.global.security.jwt.TokenClaims;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtTokenProvider jwt;
    private final JwtProperties jwtProps;
    private final RefreshTokenJtiStore rtJtiStore;
    private final OAuthCredentialService credentialService;

    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.JWT_MISSING);
        }

        TokenClaims claims;
        try {
            claims = jwt.decodeRefresh(refreshToken);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.JWT_INVALID);
        }

        UUID userId = claims.userId();
        String jti  = claims.jti();

        if (!rtJtiStore.matches(userId, jti)) {
            rtJtiStore.clear(userId);
            throw new BusinessException(ErrorCode.JWT_INVALID);
        }

        String newAt = jwt.createAccessToken(userId);
        String newRt = jwt.createRefreshToken(userId);
        TokenClaims newRtClaims = jwt.decodeRefresh(newRt);

        rtJtiStore.save(
                userId,
                newRtClaims.jti(),
                Duration.ofMillis(jwtProps.refreshTokenExpiration())
        );

        long atExpSec = jwtProps.accessTokenExpiration() / 1000;
        long rtExpSec = jwtProps.refreshTokenExpiration() / 1000;

        return new TokenPair(newAt, newRt, atExpSec, rtExpSec);
    }

    public void logout(UUID userId) {
        if (userId != null) {
            rtJtiStore.clear(userId);
        }
    }

    public void disconnectAccount(UUID userId) {
        credentialService.disconnect(userId);
        logout(userId);
    }

    public TokenPair issueTokens(UUID userId) {
        String at = jwt.createAccessToken(userId);
        String rt = jwt.createRefreshToken(userId);
        TokenClaims rtClaims = jwt.decodeRefresh(rt);

        rtJtiStore.save(
                userId,
                rtClaims.jti(),
                Duration.ofMillis(jwtProps.refreshTokenExpiration())
        );

        long atExpSec = jwtProps.accessTokenExpiration() / 1000;
        long rtExpSec = jwtProps.refreshTokenExpiration() / 1000;

        return new TokenPair(at, rt, atExpSec, rtExpSec);
    }

    public record TokenPair(
            String accessToken,
            String refreshToken,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds
    ) {}
}
