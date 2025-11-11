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
        byte[] raw = Base64.getDecoder().decode(masterKeyBase64);
        if (raw.length != 32)
            throw new IllegalStateException("❌ AES-256 key must be 32 bytes");
        keySpec = new SecretKeySpec(raw, "AES");
    }

    @Transactional
    public void saveOrUpdate(UUID userId,
                             String accessTokenPlain,
                             String refreshTokenPlain,
                             Instant accessTokenExpiresAt,
                             Set<String> scopes) {

        var user = userRepo.findById(userId).orElseThrow();
        String atEnc = encrypt(accessTokenPlain, userId);
        String rtEnc = refreshTokenPlain != null ? encrypt(refreshTokenPlain, userId) : null;
        String scopeStr = String.join(" ", scopes);

        if (credentialsRepo.existsByUser_Id(userId)) {
            credentialsRepo.updateTokens(userId, atEnc, accessTokenExpiresAt, rtEnc, scopeStr);
        } else {
            var entity = OAuthCredentials.builder()
                    .user(user)
                    .accessTokenEnc(atEnc)
                    .refreshTokenEnc(rtEnc)
                    .accessTokenExpiresAt(accessTokenExpiresAt)
                    .scopes(scopeStr)
                    .build();
            credentialsRepo.save(entity);
        }
    }

    public Optional<DecryptedCredentials> findDecrypted(UUID userId) {
        return credentialsRepo.findByUser_Id(userId)
                .map(c -> new DecryptedCredentials(
                        decrypt(c.getAccessTokenEnc(), userId),
                        decrypt(c.getRefreshTokenEnc(), userId),
                        c.getAccessTokenExpiresAt(),
                        Set.of(c.getScopes().split(" "))
                ));
    }

    public boolean hasAllScopes(UUID userId, Set<String> required) {
        return findDecrypted(userId)
                .map(c -> c.scopes().containsAll(required))
                .orElse(false);
    }

    @Transactional
    public void disconnect(UUID userId) {
        credentialsRepo.deleteByUser_Id(userId);
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
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    public record DecryptedCredentials(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Set<String> scopes
    ) {}
}
