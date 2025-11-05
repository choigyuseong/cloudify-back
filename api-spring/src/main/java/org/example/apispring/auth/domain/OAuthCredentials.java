package org.example.apispring.auth.domain;

import io.micrometer.common.lang.Nullable;
import jakarta.persistence.*;
import lombok.*;
import org.example.apispring.user.domain.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(
        name = "oauth_credentials",
        uniqueConstraints = @UniqueConstraint(name = "uk_oauth_credentials_user", columnNames = "user_id"),
        indexes = @Index(name = "idx_oauth_credentials_revoked", columnList = "revoked")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "user")
public class OAuthCredentials {

    @Id
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Lob
    @Column(name = "access_token_enc")
    private String accessTokenEnc;

    @Lob
    @Column(name = "refresh_token_enc")
    private String refreshTokenEnc;

    @Column(name = "access_token_expires_at", nullable = false)
    private Instant accessTokenExpiresAt;

    @Lob
    @Column(name = "scopes", nullable = false)
    private String scopes;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    @Builder
    OAuthCredentials(User user,
                     String accessTokenEnc,
                     String refreshTokenEnc,
                     Instant accessTokenExpiresAt,
                     String scopes) {
        this.user = user;
        this.accessTokenEnc = accessTokenEnc;
        this.refreshTokenEnc = refreshTokenEnc;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.scopes = scopes;
    }

    public void updateTokens(String accessTokenEnc, Instant accessTokenExpiresAt, @Nullable String refreshTokenEnc) {
        Objects.requireNonNull(accessTokenEnc);
        Objects.requireNonNull(accessTokenExpiresAt);
        this.accessTokenEnc = accessTokenEnc;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        if (refreshTokenEnc != null && !refreshTokenEnc.isBlank()) {
            this.refreshTokenEnc = refreshTokenEnc;
        }
    }

    public void setScopesFrom(@Nullable Set<String> scopes) {
        this.scopes = (scopes == null || scopes.isEmpty())
                ? ""
                : scopes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .sorted()
                .collect(Collectors.joining(" "));
    }


    public void revoke() {
        this.revoked = true;
        clearTokens();
    }

    public void unRevoke() {
        this.revoked = false;
    }

    public void clearTokens() {
        this.accessTokenEnc = null;      // nullable=true 마이그레이션 완료 전제
        this.refreshTokenEnc = null;
    }


}