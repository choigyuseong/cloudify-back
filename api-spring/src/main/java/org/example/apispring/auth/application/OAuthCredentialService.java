package org.example.apispring.auth.application;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.example.apispring.auth.domain.OAuthCredentials;
import org.example.apispring.auth.domain.OAuthCredentialsRepository;
import org.example.apispring.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OAuthCredentialService {

    private final OAuthCredentialsRepository credentialsRepo;
    private final UserRepository userRepo;

    @Value("${crypto.masterKeyBase64}")
    private String masterKeyBase64;

    private SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    @PostConstruct
    void init() {
        byte[] raw = Base64.getDecoder().decode(Objects.requireNonNull(masterKeyBase64));
        if (raw.length != 32) {
            // TODO: BusinessException(OAUTH_BAD_KEY)로 매핑
            throw new IllegalStateException("AES-256 key required (32 bytes)");
        }
        this.keySpec = new SecretKeySpec(raw, "AES");
    }

    @Transactional
    public void store(UUID userId,
                      String accessTokenPlain,
                      String refreshTokenPlain,
                      Instant accessTokenExpiresAt,
                      String scopes) {

        // TODO: BusinessException(OAUTH_USER_NOT_FOUND)
        var user = userRepo.findById(userId).orElseThrow();

        if (accessTokenPlain == null || accessTokenExpiresAt == null) {
            // TODO: BusinessException(OAUTH_TOKEN_MISSING)
            throw new IllegalArgumentException("access token or expiresAt is null");
        }

        String atEnc = encrypt(accessTokenPlain, userId);
        String rtEnc = refreshTokenPlain != null ? encrypt(refreshTokenPlain, userId) : null;

        if (credentialsRepo.existsByUser_Id(userId)) {
            if (rtEnc != null) {
                credentialsRepo.updateAccessAndRefresh(userId, atEnc, accessTokenExpiresAt, rtEnc);
            } else {
                credentialsRepo.updateAccessToken(userId, atEnc, accessTokenExpiresAt);
            }
        } else {
            var entity = OAuthCredentials.builder()
                    .user(user)
                    .accessTokenEnc(atEnc)
                    .refreshTokenEnc(rtEnc != null ? rtEnc : "") // TODO: 컬럼을 nullable로 바꾸고 null 그대로 저장하는 게 더 바람직
                    .accessTokenExpiresAt(accessTokenExpiresAt)
                    .scopes(scopes != null ? scopes : "")
                    .build();
            credentialsRepo.save(entity);
        }

        // TODO: 이후 단계에서 Redis 캐시에 AT를 TTL로 저장(만료-버퍼) → 중복 로직은 헬퍼로 분리 예정
    }

    public Optional<DecryptedCredentials> getDecrypted(UUID userId) {
        return credentialsRepo.findByUser_Id(userId).map(c -> new DecryptedCredentials(
                decrypt(c.getAccessTokenEnc(), userId),              // TODO: BusinessException(OAUTH_DECRYPT_FAIL)
                decrypt(emptyToNull(c.getRefreshTokenEnc()), userId),// TODO: BusinessException(OAUTH_DECRYPT_FAIL)
                c.getAccessTokenExpiresAt(),
                c.getScopes()
        ));
    }

    public boolean hasScopes(UUID userId, String... required) {
        return credentialsRepo.findByUser_Id(userId)
                .map(c -> containsAllScopes(c.getScopes(), required))
                .orElse(false);
    }

    public Set<String> missingScopes(UUID userId, String... required) {
        return credentialsRepo.findByUser_Id(userId)
                .map(c -> missingScopesFrom(c.getScopes(), required))
                .orElseGet(() -> normalized(required));
    }

    private String encrypt(String plaintext, UUID userId) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            cipher.updateAAD(("GOOGLE|" + userId).getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer bb = ByteBuffer.allocate(12 + ct.length);
            bb.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(bb.array());
        } catch (Exception e) {
            // TODO: BusinessException(OAUTH_ENCRYPT_FAIL)
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    private String decrypt(String ciphertextBase64, UUID userId) {
        if (ciphertextBase64 == null || ciphertextBase64.isBlank()) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(ciphertextBase64);
            byte[] iv = Arrays.copyOfRange(raw, 0, 12);
            byte[] ct = Arrays.copyOfRange(raw, 12, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            cipher.updateAAD(("GOOGLE|" + userId).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // TODO: BusinessException(OAUTH_DECRYPT_FAIL)
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean containsAllScopes(String granted, String... required) {
        if (required == null || required.length == 0) return true;
        Set<String> g = normalized(granted);
        for (String r : required) {
            if (r == null || r.isBlank()) continue;
            if (!g.contains(r)) return false;
        }
        return true;
    }

    private static Set<String> missingScopesFrom(String granted, String... required) {
        Set<String> g = normalized(granted);
        return normalized(required).stream().filter(r -> !g.contains(r)).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> normalized(String scopes) {
        if (scopes == null || scopes.isBlank()) return Set.of();
        return Arrays.stream(scopes.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> normalized(String... scopes) {
        if (scopes == null || scopes.length == 0) return Set.of();
        return Arrays.stream(scopes)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public record DecryptedCredentials(String accessToken, String refreshToken, Instant accessTokenExpiresAt, String scopes) {}
}
