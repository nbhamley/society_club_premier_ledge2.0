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

import java.time.LocalDateTime;

/**
 * Column names are explicit: Spring's naming strategy would turn "teamARuns" into
 * "team_aruns", which does not match the schema.
 */
@Entity
@Table(name = "matches")
@Getter
@Setter
@NoArgsConstructor
public class Match extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_a_id", nullable = false)
    private Team teamA;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_b_id", nullable = false)
    private Team teamB;

    @Column(name = "match_date")
    private LocalDateTime matchDate;

    private String venue;

    @Enumerated(EnumType.STRING)
    private MatchStage stage = MatchStage.LEAGUE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchStatus status = MatchStatus.SCHEDULED;

    @Column(name = "team_a_runs")
    private Integer teamARuns;

    @Column(name = "team_a_wickets")
    private Integer teamAWickets;

    @Column(name = "team_a_overs")
    private Double teamAOvers;

    @Column(name = "team_b_runs")
    private Integer teamBRuns;

    @Column(name = "team_b_wickets")
    private Integer teamBWickets;

    @Column(name = "team_b_overs")
    private Double teamBOvers;

    /** Null for a tie, no-result or an unplayed match. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_team_id")
    private Team winnerTeam;

    @Column(name = "result_summary")
    private String resultSummary;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    public boolean isCompleted() {
        return status == MatchStatus.COMPLETED;
    }

    public boolean involves(Team team) {
        return team != null && (team.equals(teamA) || team.equals(teamB));
    }
}