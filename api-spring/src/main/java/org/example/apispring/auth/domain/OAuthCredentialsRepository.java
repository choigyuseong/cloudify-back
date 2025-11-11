package org.example.apispring.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OAuthCredentialsRepository extends JpaRepository<OAuthCredentials, UUID> {

    boolean existsByUser_Id(UUID userId);

    Optional<OAuthCredentials> findByUser_Id(UUID userId);

    @Transactional
    void deleteByUser_Id(UUID userId);

    @Transactional
    @Modifying
    @Query("""
        UPDATE OAuthCredentials c
           SET c.accessTokenEnc = :accessTokenEnc,
               c.accessTokenExpiresAt = :accessTokenExpiresAt,
               c.refreshTokenEnc = :refreshTokenEnc,
               c.scopes = :scopes
         WHERE c.user.id = :userId
           AND c.revoked = false
    """)
    void updateTokens(UUID userId,
                      String accessTokenEnc,
                      Instant accessTokenExpiresAt,
                      String refreshTokenEnc,
                      String scopes);
}
