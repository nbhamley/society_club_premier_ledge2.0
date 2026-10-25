package com.scpl.service;

import com.scpl.dto.StandingRow;
import com.scpl.model.Match;
import com.scpl.model.MatchStatus;
import com.scpl.model.Team;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure calculation: no repositories, so it is trivially unit-testable (Phase 11).
 * Win = 2 points, tie = 1, loss = 0. Net run rate follows the usual rule: a side that
 * is bowled out is charged the full quota of overs, not the overs it actually faced.
 */
@Component
public class StandingsCalculator {

    public static final int WIN_POINTS = 2;
    public static final int TIE_POINTS = 1;
    private static final int ALL_OUT_WICKETS = 10;

    private static final class Tally {
        final Team team;
        int played;
        int won;
        int lost;
        int tied;
        double runsFor;
        double oversFor;
        double runsAgainst;
        double oversAgainst;

        Tally(Team team) {
            this.team = team;
        }

        int points() {
            return won * WIN_POINTS + tied * TIE_POINTS;
        }

        double netRunRate() {
            double scoring = oversFor > 0 ? runsFor / oversFor : 0;
            double conceding = oversAgainst > 0 ? runsAgainst / oversAgainst : 0;
            return scoring - conceding;
        }
    }

    public List<StandingRow> calculate(int oversPerMatch, Collection<Team> teams, Collection<Match> matches) {
        Map<Long, Tally> table = new LinkedHashMap<>();
        for (Team t : teams) {
            table.put(t.getId(), new Tally(t));
        }
        for (Match m : matches) {
            if (m.getStatus() != MatchStatus.COMPLETED || !hasScores(m)) {
                continue;
            }
            Tally a = table.get(m.getTeamA().getId());
            Tally b = table.get(m.getTeamB().getId());
            if (a == null || b == null) {
                continue;
            }
            double aOvers = effectiveOvers(m.getTeamAOvers(), m.getTeamAWickets(), oversPerMatch);
            double bOvers = effectiveOvers(m.getTeamBOvers(), m.getTeamBWickets(), oversPerMatch);
            a.played++;
            b.played++;
            a.runsFor += m.getTeamARuns();
            a.oversFor += aOvers;
            a.runsAgainst += m.getTeamBRuns();
            a.oversAgainst += bOvers;
            b.runsFor += m.getTeamBRuns();
            b.oversFor += bOvers;
            b.runsAgainst += m.getTeamARuns();
            b.oversAgainst += aOvers;

            Long winnerId = winnerId(m);
            if (winnerId == null) {
                a.tied++;
                b.tied++;
            } else if (winnerId.equals(a.team.getId())) {
                a.won++;
                b.lost++;
            } else {
                b.won++;
                a.lost++;
            }
        }
        List<StandingRow> rows = new ArrayList<>();
        for (Tally t : table.values()) {
            rows.add(new StandingRow(t.team.getId(), t.team.getName(), t.team.getDisplayShortName(),
                    t.team.getColor(), t.played, t.won, t.lost, t.tied, t.points(), t.netRunRate()));
        }
        rows.sort(Comparator.comparingInt(StandingRow::points).reversed()
                .thenComparing(Comparator.comparingDouble(StandingRow::netRunRate).reversed())
                .thenComparing(StandingRow::teamName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** 9.3 in cricket notation is 9 overs and 3 balls, which is 9.5 in decimal overs, not 9.3. */
    public static double toDecimalOvers(double cricketOvers) {
        int whole = (int) Math.floor(cricketOvers);
        int balls = (int) Math.round((cricketOvers - whole) * 10);
        return whole + balls / 6.0;
    }

    private static double effectiveOvers(Double overs, Integer wickets, int quota) {
        if (wickets != null && wickets >= ALL_OUT_WICKETS) {
            return quota;
        }
        return overs == null ? 0 : toDecimalOvers(overs);
    }

    private static boolean hasScores(Match m) {
        return m.getTeamARuns() != null && m.getTeamBRuns() != null;
    }

    /** Null means a tie. An explicit winner (for example after a super over) beats the raw scores. */
    private static Long winnerId(Match m) {
        if (m.getWinnerTeam() != null) {
            return m.getWinnerTeam().getId();
        }
        int cmp = Integer.compare(m.getTeamARuns(), m.getTeamBRuns());
        if (cmp == 0) {
            return null;
        }
        return cmp > 0 ? m.getTeamA().getId() : m.getTeamB().getId();
    }
}