package com.scpl.service;

import com.scpl.dto.StandingRow;
import com.scpl.model.Match;
import com.scpl.model.MatchStatus;
import com.scpl.model.Team;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StandingsCalculatorTest {

    private final StandingsCalculator calc = new StandingsCalculator();

    private static Team team(long id, String name) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private static Match match(Team a, Team b, int runsA, int wicketsA, double oversA, int runsB, int wicketsB, double oversB) {
        Match m = new Match();
        m.setTeamA(a);
        m.setTeamB(b);
        m.setStatus(MatchStatus.COMPLETED);
        m.setTeamARuns(runsA);
        m.setTeamAWickets(wicketsA);
        m.setTeamAOvers(oversA);
        m.setTeamBRuns(runsB);
        m.setTeamBWickets(wicketsB);
        m.setTeamBOvers(oversB);
        return m;
    }

    private static StandingRow row(List<StandingRow> rows, String name) {
        return rows.stream().filter(r -> r.teamName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aWinIsWorthTwoPointsAndALossNone() {
        Team a = team(1, "Alpha");
        Team b = team(2, "Beta");
        List<StandingRow> rows = calc.calculate(10, List.of(a, b), List.of(match(a, b, 92, 4, 10.0, 85, 7, 10.0)));
        assertThat(rows).extracting(StandingRow::teamName).containsExactly("Alpha", "Beta");
        assertThat(row(rows, "Alpha").points()).isEqualTo(2);
        assertThat(row(rows, "Alpha").won()).isEqualTo(1);
        assertThat(row(rows, "Beta").points()).isZero();
        assertThat(row(rows, "Beta").lost()).isEqualTo(1);
        assertThat(row(rows, "Beta").played()).isEqualTo(1);
    }

    @Test
    void aTieGivesEachSideOnePoint() {
        Team a = team(1, "Alpha");
        Team b = team(2, "Beta");
        List<StandingRow> rows = calc.calculate(10, List.of(a, b), List.of(match(a, b, 110, 5, 10.0, 110, 6, 10.0)));
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.tied()).isEqualTo(1);
            assertThat(r.points()).isEqualTo(1);
        });
    }

    @Test
    void anExplicitWinnerBeatsTheRawScores_forExampleAfterASuperOver() {
        Team a = team(1, "Alpha");
        Team b = team(2, "Beta");
        Match m = match(a, b, 100, 5, 10.0, 100, 6, 10.0);
        m.setWinnerTeam(b);
        List<StandingRow> rows = calc.calculate(10, List.of(a, b), List.of(m));
        assertThat(row(rows, "Beta").won()).isEqualTo(1);
        assertThat(row(rows, "Alpha").lost()).isEqualTo(1);
    }

    @Test
    void anAllOutSideIsChargedTheFullQuotaOfOvers() {
        Team a = team(1, "Alpha");
        Team b = team(2, "Beta");
        // Beta were bowled out in 7.3 overs but are charged 10: Alpha 100/10 - 80/10 = +2, Beta the opposite.
        List<StandingRow> rows = calc.calculate(10, List.of(a, b), List.of(match(a, b, 100, 4, 10.0, 80, 10, 7.3)));
        assertThat(row(rows, "Alpha").netRunRate()).isCloseTo(2.0, within(1e-9));
        assertThat(row(rows, "Beta").netRunRate()).isCloseTo(-2.0, within(1e-9));
    }

    @Test
    void cricketOversUseBallsNotDecimals() {
        assertThat(StandingsCalculator.toDecimalOvers(9.3)).isCloseTo(9.5, within(1e-9));
        assertThat(StandingsCalculator.toDecimalOvers(9.5)).isCloseTo(9 + 5 / 6.0, within(1e-9));
        assertThat(StandingsCalculator.toDecimalOvers(10.0)).isCloseTo(10.0, within(1e-9));
        assertThat(StandingsCalculator.toDecimalOvers(0.0)).isZero();

        Team a = team(1, "Alpha");
        Team b = team(2, "Beta");
        List<StandingRow> rows = calc.calculate(10, List.of(a, b), List.of(match(a, b, 100, 4, 10.0, 80, 9, 9.3)));
        // Beta: 80 runs in 9.3 overs (= 9.5 decimal overs) while Alpha scored 100 in 10
        assertThat(row(rows, "Beta").netRunRate()).isCloseTo(80 / 9.5 - 100 / 10.0, within(1e-9));
    }

    @Test
    void equalPointsAreSeparatedByNetRunRate() {
        Team a = team(1, "Alpha");
        Team b = team(2, "Beta");
        Team c = team(3, "Gamma");
        List<StandingRow> rows = calc.calculate(10, List.of(b, c, a), List.of(
                match(a, b, 120, 2, 10.0, 60, 10, 8.0),      // Alpha wins big
                match(c, b, 70, 3, 10.0, 60, 4, 10.0)));      // Gamma wins narrowly
        assertThat(rows).extracting(StandingRow::teamName).containsExactly("Alpha", "Gamma", "Beta");
        assertThat(row(rows, "Alpha").points()).isEqualTo(row(rows, "Gamma").points());
        assertThat(row(rows, "Alpha").netRunRate()).isGreaterThan(row(rows, "Gamma").netRunRate());
    }

    @Test
    void onlyCompletedMatchesWithScoresCount() {
        Team a = team(1, "Alpha");
        Team b = team(2, "Beta");
        Match scheduled = match(a, b, 100, 1, 10.0, 50, 1, 10.0);
        scheduled.setStatus(MatchStatus.SCHEDULED);
        Match noScores = new Match();
        noScores.setTeamA(a);
        noScores.setTeamB(b);
        noScores.setStatus(MatchStatus.COMPLETED);
        List<StandingRow> rows = calc.calculate(10, List.of(a, b), List.of(scheduled, noScores));
        assertThat(rows).allSatisfy(r -> assertThat(r.played()).isZero());
    }

    @Test
    void aMatchAgainstATeamNotInTheTableIsIgnored() {
        Team a = team(1, "Alpha");
        Team stranger = team(99, "Stranger");
        List<StandingRow> rows = calc.calculate(10, List.of(a), List.of(match(a, stranger, 100, 1, 10.0, 50, 1, 10.0)));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).played()).isZero();
    }

    @Test
    void teamsWithNothingPlayedAreSortedByName_andAnEmptySeasonIsEmpty() {
        List<StandingRow> rows = calc.calculate(10, List.of(team(1, "Zed"), team(2, "Alpha")), List.of());
        assertThat(rows).extracting(StandingRow::teamName).containsExactly("Alpha", "Zed");
        assertThat(calc.calculate(10, List.of(), List.of())).isEmpty();
    }
}