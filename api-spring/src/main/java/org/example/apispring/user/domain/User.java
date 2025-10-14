package org.example.apispring.user.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(
        name = "users",
        indexes = @Index(name = "idx_users_email", columnList = "email"),
        uniqueConstraints = @UniqueConstraint(name = "uk_users_sub", columnNames = "sub")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@EqualsAndHashCode(of = "sub")
public class User {

    @Id
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 255)
    private String sub;

    @Column(length = 320)
    private String email;

    @Column(length = 100)
    private String name;

    @Column(name = "picture_url", length = 1024)
    private String pictureUrl;

    @Builder
    User(String sub, String email, String name, String pictureUrl) {
        this.sub = sub;
        this.email = email;
        this.name = name;
        this.pictureUrl = pictureUrl;
    }

    public void updateProfile(String email, String name, String pictureUrl) {
        this.email = email;
        this.name = name;
        this.pictureUrl = pictureUrl;
    }
}