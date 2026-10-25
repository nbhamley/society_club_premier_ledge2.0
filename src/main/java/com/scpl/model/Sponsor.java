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

@Entity
@Table(name = "sponsors")
@Getter
@Setter
@NoArgsConstructor
public class Sponsor extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id")
    private Season season;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private SponsorTier tier;

    @Column(name = "logo_url")
    private String logoUrl;

    private String website;

    @Column(nullable = false)
    private Double amount = 0.0;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }
}