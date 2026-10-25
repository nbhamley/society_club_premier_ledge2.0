package com.scpl.service;

import com.scpl.dto.MatchRequest;
import com.scpl.dto.MatchResultRequest;
import com.scpl.dto.StandingRow;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.Match;
import com.scpl.model.MatchStage;
import com.scpl.model.MatchStatus;
import com.scpl.model.Season;
import com.scpl.model.Team;
import com.scpl.model.User;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.util.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MatchService {

    private static final int MAX_WICKETS = 10;

    private final MatchRepository matches;
    private final SeasonRepository seasons;
    private final TeamRepository teams;
    private final StandingsCalculator standings;
    private final AuditService audit;

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<Match> forSeason(Long societyId, Long seasonId) {
        return matches.findBySeason_IdOrderByMatchDateAsc(seasonId).stream()
                .filter(m -> societyId.equals(m.getSocietyId())).toList();
    }

    @Transactional(readOnly = true)
    public List<Match> forSociety(Long societyId) {
        return matches.findBySociety_IdOrderByMatchDateAsc(societyId);
    }

    @Transactional(readOnly = true)
    public List<Match> upcoming(Long societyId) {
        return matches.findBySociety_IdAndStatusAndMatchDateAfterOrderByMatchDateAsc(
                societyId, MatchStatus.SCHEDULED, LocalDateTime.now());
    }

    /** "Next fixture" KPI tile. */
    @Transactional(readOnly = true)
    public Optional<Match> nextFixture(Long societyId) {
        return matches.findFirstBySociety_IdAndStatusAndMatchDateAfterOrderByMatchDateAsc(
                societyId, MatchStatus.SCHEDULED, LocalDateTime.now());
    }

    /** Public match page; the owning society is part of the graph so the controller can scope-check it. */
    @Transactional(readOnly = true)
    public Match publicDetail(Long matchId) {
        return matches.findWithDetailsById(matchId).orElseThrow(() -> new NotFoundException("Match not found"));
    }

    @Transactional(readOnly = true)
    public Match get(Long societyId, Long matchId) {
        return matches.findByIdAndSociety_Id(matchId, societyId)
                .orElseThrow(() -> new NotFoundException("Match not found"));
    }

    @Transactional(readOnly = true)
    public List<StandingRow> standings(Long societyId, Long seasonId) {
        Season season = seasons.findByIdAndSociety_Id(seasonId, societyId)
                .orElseThrow(() -> new NotFoundException("Season not found"));
        List<Team> seasonTeams = teams.findBySeason_IdOrderByNameAsc(seasonId);
        List<Match> completed = matches.findBySeason_IdAndStatus(seasonId, MatchStatus.COMPLETED);
        return standings.calculate(season.getOversPerMatch(), seasonTeams, completed);
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public Match schedule(User actor, Long societyId, MatchRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        Season season = seasons.findByIdAndSociety_Id(r.seasonId(), societyId)
                .orElseThrow(() -> new NotFoundException("Season not found"));
        Team a = team(societyId, r.teamAId());
        Team b = team(societyId, r.teamBId());
        validatePair(season, a, b);

        Match m = new Match();
        m.setSociety(season.getSociety());
        m.setSeason(season);
        m.setTeamA(a);
        m.setTeamB(b);
        m.setMatchDate(r.matchDate());
        m.setVenue(Text.clean(r.venue()));
        m.setStage(r.stage() == null ? MatchStage.LEAGUE : r.stage());
        m.setStatus(MatchStatus.SCHEDULED);
        m = matches.save(m);
        audit.record(actor, societyId, AuditActions.MATCH_CREATED, "MATCH", m.getId(), null,
                Map.of("teamA", a.getName(), "teamB", b.getName()));
        return m;
    }

    /** Changes fixture details (date, venue, stage, teams) but never a recorded result. */
    @Transactional
    public Match reschedule(User actor, Long societyId, Long matchId, MatchRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        Match m = get(societyId, matchId);
        if (m.isCompleted()) {
            throw new BusinessRuleException("A completed match cannot be rescheduled");
        }
        Team a = team(societyId, r.teamAId());
        Team b = team(societyId, r.teamBId());
        validatePair(m.getSeason(), a, b);
        Map<String, Object> before = Map.of("teamA", m.getTeamA().getName(), "teamB", m.getTeamB().getName());
        m.setTeamA(a);
        m.setTeamB(b);
        m.setMatchDate(r.matchDate());
        m.setVenue(Text.clean(r.venue()));
        if (r.stage() != null) {
            m.setStage(r.stage());
        }
        matches.save(m);
        audit.record(actor, societyId, AuditActions.MATCH_UPDATED, "MATCH", matchId, before,
                Map.of("teamA", a.getName(), "teamB", b.getName()));
        return m;
    }

    /** Records (or corrects) the result. The winner is derived from the scores, never typed in. */
    @Transactional
    public Match recordResult(User actor, Long societyId, Long matchId, MatchResultRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        Match m = get(societyId, matchId);
        if (m.getStatus() == MatchStatus.CANCELLED) {
            throw new BusinessRuleException("A cancelled match cannot have a result");
        }
        int quota = m.getSeason().getOversPerMatch();
        validateInnings(r.teamARuns(), r.teamAWickets(), r.teamAOvers(), quota, m.getTeamA().getName());
        validateInnings(r.teamBRuns(), r.teamBWickets(), r.teamBOvers(), quota, m.getTeamB().getName());

        Map<String, Object> before = Map.of("status", m.getStatus().name(),
                "winner", m.getWinnerTeam() == null ? "none" : m.getWinnerTeam().getName());

        m.setTeamARuns(r.teamARuns());
        m.setTeamAWickets(r.teamAWickets());
        m.setTeamAOvers(r.teamAOvers());
        m.setTeamBRuns(r.teamBRuns());
        m.setTeamBWickets(r.teamBWickets());
        m.setTeamBOvers(r.teamBOvers());

        int cmp = Integer.compare(r.teamARuns(), r.teamBRuns());
        Team winner = cmp > 0 ? m.getTeamA() : cmp < 0 ? m.getTeamB() : null;
        m.setWinnerTeam(winner);
        m.setStatus(MatchStatus.COMPLETED);
        m.setResultSummary(Text.clean(r.resultSummary()) != null ? r.resultSummary().trim() : summarise(m, winner));
        matches.save(m);

        audit.record(actor, societyId, AuditActions.MATCH_UPDATED, "MATCH", matchId, before,
                Map.of("status", "COMPLETED", "winner", winner == null ? "tie" : winner.getName()));
        return m;
    }

    @Transactional
    public Match cancel(User actor, Long societyId, Long matchId) {
        Tenancy.requireAdminOf(actor, societyId);
        Match m = get(societyId, matchId);
        if (m.isCompleted()) {
            throw new BusinessRuleException("A completed match cannot be cancelled. Delete it instead.");
        }
        m.setStatus(MatchStatus.CANCELLED);
        matches.save(m);
        audit.record(actor, societyId, AuditActions.MATCH_UPDATED, "MATCH", matchId, null, Map.of("status", "CANCELLED"));
        return m;
    }

    @Transactional
    public void delete(User actor, Long societyId, Long matchId) {
        Tenancy.requireAdminOf(actor, societyId);
        Match m = get(societyId, matchId);
        Map<String, Object> before = Map.of("teamA", m.getTeamA().getName(), "teamB", m.getTeamB().getName(),
                "status", m.getStatus().name());
        matches.delete(m);
        audit.record(actor, societyId, AuditActions.MATCH_DELETED, "MATCH", matchId, before, null);
    }

    // ------------------------------------------------------------------ internals

    private Team team(Long societyId, Long teamId) {
        return teams.findByIdAndSociety_Id(teamId, societyId)
                .orElseThrow(() -> new NotFoundException("Team not found"));
    }

    private void validatePair(Season season, Team a, Team b) {
        if (a.getId().equals(b.getId())) {
            throw new BusinessRuleException("A team cannot play itself");
        }
        if (!a.getSeasonId().equals(season.getId()) || !b.getSeasonId().equals(season.getId())) {
            throw new BusinessRuleException("Both teams must belong to the match's season");
        }
    }

    private void validateInnings(Integer runs, Integer wickets, Double overs, int quota, String teamName) {
        if (runs < 0) {
            throw new BusinessRuleException(teamName + ": runs cannot be negative");
        }
        if (wickets < 0 || wickets > MAX_WICKETS) {
            throw new BusinessRuleException(teamName + ": wickets must be between 0 and " + MAX_WICKETS);
        }
        if (overs < 0 || overs > quota) {
            throw new BusinessRuleException(teamName + ": overs must be between 0 and " + quota);
        }
        // Cricket notation: the digit after the point counts balls, so it can only be 0 to 5.
        long balls = Math.round((overs - Math.floor(overs)) * 10);
        if (balls > 5) {
            throw new BusinessRuleException(teamName + ": an over has six balls, so 0.6 or more is invalid (use the next over)");
        }
    }

    private String summarise(Match m, Team winner) {
        String line = String.format(Locale.ROOT, "%s %d/%d (%.1f ov) vs %s %d/%d (%.1f ov)",
                m.getTeamA().getName(), m.getTeamARuns(), m.getTeamAWickets(), m.getTeamAOvers(),
                m.getTeamB().getName(), m.getTeamBRuns(), m.getTeamBWickets(), m.getTeamBOvers());
        return line + (winner == null ? " - match tied" : " - " + winner.getName() + " won");
    }
}