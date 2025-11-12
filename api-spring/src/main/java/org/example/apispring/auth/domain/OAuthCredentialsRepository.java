package org.example.apispring.auth.domain;

import org.example.apispring.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OAuthCredentialsRepository extends JpaRepository<OAuthCredentials, UUID> {

    Optional<OAuthCredentials> findByUser(User user);

    Optional<OAuthCredentials> findByUser_Id(UUID userId);

    boolean existsByUser_Id(UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
                update OAuthCredentials c
                   set c.accessTokenEnc = :accessTokenEnc,
                       c.accessTokenExpiresAt = :accessTokenExpiresAt
                 where c.user.id = :userId
                   and c.revoked = false
            """)
    int updateAccessToken(@Param("userId") UUID userId,
                          @Param("accessTokenEnc") String accessTokenEnc,
                          @Param("accessTokenExpiresAt") Instant accessTokenExpiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
                update OAuthCredentials c
                   set c.accessTokenEnc = :accessTokenEnc,
                       c.accessTokenExpiresAt = :accessTokenExpiresAt,
                       c.refreshTokenEnc = :refreshTokenEnc
                 where c.user.id = :userId
                   and c.revoked = false
            """)
    int updateAccessAndRefresh(@Param("userId") UUID userId,
                               @Param("accessTokenEnc") String accessTokenEnc,
                               @Param("accessTokenExpiresAt") Instant accessTokenExpiresAt,
                               @Param("refreshTokenEnc") String refreshTokenEnc);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
                update OAuthCredentials c
                   set c.revoked = true
                 where c.user.id = :userId
            """)
    int markRevoked(@Param("userId") UUID userId);
}