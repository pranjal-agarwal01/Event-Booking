package com.booking.auth;

import com.booking.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh tokens are stored so they can be revoked. Access tokens are not stored -
 * that is the trade: they are fast to verify but live until they expire.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RefreshToken() {
        // required by JPA
    }

    public RefreshToken(User user, Instant expiresAt) {
        this.token = UUID.randomUUID();
        this.user = user;
        this.expiresAt = expiresAt;
    }

    public UUID getToken() {
        return token;
    }

    public User getUser() {
        return user;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void revoke() {
        this.revoked = true;
    }

    public boolean isUsable() {
        return !revoked && expiresAt.isAfter(Instant.now());
    }
}
