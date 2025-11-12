package org.example.apispring.auth.application;

import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.example.apispring.auth.domain.OAuthCredentials;
import org.example.apispring.auth.domain.OAuthCredentialsRepository;
import org.example.apispring.auth.infra.GoogleTokenRevoker;
import org.example.apispring.global.util.TokenCrypto;
import org.example.apispring.user.domain.User;
import org.example.apispring.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OAuthCredentialService {

    private final OAuthCredentialsRepository credRepository;
    private final UserRepository userRepository;
    private final TokenCrypto crypto;
    private final GoogleTokenRevoker revoker;

    @Transactional
    public void saveOrUpdate(
            UUID userId,
            String googleAccessTokenPlain,
            @Nullable String googleRefreshTokenPlain,
            Instant accessTokenExpiresAt,
            Set<String> scopes
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));

        // 암호화 + 정규화
        String atEnc = crypto.encrypt(Objects.requireNonNull(googleAccessTokenPlain));
        String rtEnc = (googleRefreshTokenPlain != null && !googleRefreshTokenPlain.isBlank())
                ? crypto.encrypt(googleRefreshTokenPlain)
                : null;

        var credsOpt = credRepository.findByUser(user);

        // 해당 유저의 자격증명을 처음 저장하는 경우 ( RT 받음 )
        if (credsOpt.isEmpty()) {
            if (rtEnc == null) {
                throw new IllegalStateException("Refresh token required on first save (use access_type=offline)");
            }
            OAuthCredentials c = OAuthCredentials.builder()
                    .user(user)
                    .accessTokenEnc(atEnc)
                    .refreshTokenEnc(rtEnc)
                    .accessTokenExpiresAt(accessTokenExpiresAt)
                    .scopes("")
                    .build();
            c.setScopesFrom(scopes);
            credRepository.save(c);
            return;
        }

        OAuthCredentials c = credsOpt.get();

        // 사용자가 권한 회수한 경우 ( RT 받음 )
        if (c.isRevoked()) {
            if (rtEnc == null) {
                throw new IllegalStateException("Account was revoked. New offline consent (refresh token) required.");
            }
            c.unRevoke();
            c.updateTokens(atEnc, accessTokenExpiresAt, rtEnc);
            c.setScopesFrom(scopes);
            return;
        }

        c.updateTokens(atEnc, accessTokenExpiresAt, rtEnc);
        c.setScopesFrom(scopes);
    }

    // 복호화
    @Transactional(readOnly = true)
    public Optional<GoogleTokenSnapshot> loadDecrypted(UUID userId) {
        return credRepository.findByUser_Id(userId)
                .filter(c -> !c.isRevoked())
                .map(c -> new GoogleTokenSnapshot(
                        userId,
                        c.getAccessTokenEnc() == null ? null : crypto.decrypt(c.getAccessTokenEnc()),
                        c.getRefreshTokenEnc() == null ? null : crypto.decrypt(c.getRefreshTokenEnc()),
                        c.getAccessTokenExpiresAt(),
                        splitScopes(c.getScopes())
                ));
    }

    @Transactional(readOnly = true)
    public boolean hasAllScopes(UUID userId, Set<String> required) {
        return credRepository.findByUser_Id(userId)
                .filter(c -> !c.isRevoked())
                .map(c -> splitScopes(c.getScopes()).containsAll(required))
                .orElse(false);
    }

    private Set<String> splitScopes(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptySet();
        return Arrays.stream(raw.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public record GoogleTokenSnapshot(
            UUID userId,
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Set<String> scopes
    ) {
    }

    @Transactional
    public void disconnect(UUID userId) {
        credRepository.findByUser_Id(userId).ifPresent(c -> {
            // 1) 구글 권한 철회 (RT 복호화 후)
            String plainRt = c.getRefreshTokenEnc() == null ? null : crypto.decrypt(c.getRefreshTokenEnc());
            revoker.revokeRefreshToken(plainRt);

            // 2) 로컬 자격 증명 폐기
            c.revoke(); // tokens=null + revoked=true (nullable=true 전제)
        });
    }
}