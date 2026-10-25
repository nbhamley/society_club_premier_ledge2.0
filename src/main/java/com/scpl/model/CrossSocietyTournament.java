package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** Scaffold only: UI and endpoints are gated by feature.cross-society.enabled. */
@Entity
@Table(name = "cross_society_tournaments")
@Getter
@Setter
@NoArgsConstructor
public class CrossSocietyTournament extends TimestampedEntity {

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_society_id", nullable = false)
    private Society hostSociety;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TournamentStatus status = TournamentStatus.PROPOSED;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "tournament_societies",
            joinColumns = @JoinColumn(name = "tournament_id"),
            inverseJoinColumns = @JoinColumn(name = "society_id"))
    private Set<Society> participatingSocieties = new HashSet<>();

    private String description;
}