package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "teams")
@Getter
@Setter
@NoArgsConstructor
public class Team extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @Column(nullable = false)
    private String name;

    @Column(name = "short_name")
    private String shortName;

    /** Hex colour such as #1e88e5, used for badges and share cards. */
    private String color;

    @Column(name = "logo_url")
    private String logoUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "captain_player_id")
    private Player captain;

    @Column(name = "purse_remaining", nullable = false)
    private Double purseRemaining = 0.0;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    public Long getSeasonId() {
        return season == null ? null : season.getId();
    }

    public String getDisplayShortName() {
        return shortName != null && !shortName.isBlank() ? shortName : name;
    }
}