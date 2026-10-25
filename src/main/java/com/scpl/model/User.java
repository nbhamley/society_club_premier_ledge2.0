package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Login identity. Deliberately does not implement UserDetails: the model stays free of
 * Spring Security types and the adapter lives in the security package.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends TimestampedEntity implements SocietyScoped {

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String email;

    @Column(name = "full_name")
    private String fullName;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformRole role;

    /** Null only for PLATFORM_ADMIN. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "society_id")
    private Society society;

    /** Set when an admin or player login is also a registered player (dual role, §6). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id")
    private Player player;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    public Long getPlayerId() {
        return player == null ? null : player.getId();
    }

    public boolean isPlatformAdmin() {
        return role == PlatformRole.PLATFORM_ADMIN;
    }

    public boolean isSocietyAdmin() {
        return role == PlatformRole.SOCIETY_ADMIN;
    }

    public boolean isPlayingAsPlayer() {
        return player != null;
    }
}