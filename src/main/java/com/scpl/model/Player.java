package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PII fields (email, phone, dateOfBirth, address, flatNumber) must never reach a public
 * or cross-society view. Services map to PublicPlayerView (Phase 4) before rendering, so
 * templates never get the chance to leak them (§14).
 */
@Entity
@Table(name = "players")
@Getter
@Setter
@NoArgsConstructor
public class Player extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(nullable = false)
    private String name;

    // ---- PII block ----
    private String email;
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String address;

    @Column(name = "flat_number")
    private String flatNumber;
    // ---- end PII block ----

    @Enumerated(EnumType.STRING)
    private PlayerRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "batting_style")
    private BattingStyle battingStyle;

    @Enumerated(EnumType.STRING)
    @Column(name = "bowling_style")
    private BowlingStyle bowlingStyle;

    @Column(name = "jersey_number")
    private Integer jerseyNumber;

    @Column(name = "base_price", nullable = false)
    private Double basePrice = 0.0;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "matches_played", nullable = false)
    private Integer matchesPlayed = 0;

    @Column(nullable = false)
    private Integer runs = 0;

    @Column(nullable = false)
    private Integer wickets = 0;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    public Long getTeamId() {
        return team == null ? null : team.getId();
    }
}