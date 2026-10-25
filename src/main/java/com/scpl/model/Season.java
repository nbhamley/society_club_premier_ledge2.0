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

@Entity
@Table(name = "seasons")
@Getter
@Setter
@NoArgsConstructor
public class Season extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @Column(nullable = false)
    private String name;

    @Column(name = "year")
    private Integer year;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeasonStatus status = SeasonStatus.UPCOMING;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "registration_open", nullable = false)
    private Boolean registrationOpen = Boolean.FALSE;

    @Column(name = "overs_per_match", nullable = false)
    private Integer oversPerMatch = 10;

    /** Auction budget given to every team at season start. */
    @Column(name = "team_purse", nullable = false)
    private Double teamPurse = 0.0;

    /** Planned total spend; the bills dashboard compares actuals against this. */
    @Column(name = "planned_budget", nullable = false)
    private Double plannedBudget = 0.0;

    private String description;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    public boolean isCompleted() {
        return status == SeasonStatus.COMPLETED;
    }
}