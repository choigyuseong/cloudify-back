package org.example.apispring.auth.domain;

import jakarta.persistence.*;
import lombok.*;
import org.example.apispring.user.domain.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

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
    @Column(name = "access_token_enc", nullable = false)
    private String accessTokenEnc;

    @Lob
    @Column(name = "refresh_token_enc", nullable = false)
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
}