package com.scpl.repo;

import com.scpl.model.Match;
import com.scpl.model.MatchStatus;
import org.springframework.data.jpa.repository.EntityGraph;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MatchRepository extends SocietyScopedRepository<Match, Long> {

    /** Every match read feeds a template that prints team names, so teams are always fetched. */
    @EntityGraph(attributePaths = {"teamA", "teamB", "winnerTeam"})
    List<Match> findBySeason_IdOrderByMatchDateAsc(Long seasonId);

    @EntityGraph(attributePaths = {"teamA", "teamB", "winnerTeam"})
    List<Match> findBySociety_IdOrderByMatchDateAsc(Long societyId);

    /** Input for StandingsCalculator: completed matches only. */
    @EntityGraph(attributePaths = {"teamA", "teamB", "winnerTeam"})
    List<Match> findBySeason_IdAndStatus(Long seasonId, MatchStatus status);

    @EntityGraph(attributePaths = {"teamA", "teamB", "winnerTeam", "season", "society"})
    Optional<Match> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"teamA", "teamB", "winnerTeam"})
    List<Match> findBySociety_IdAndStatusAndMatchDateAfterOrderByMatchDateAsc(
            Long societyId, MatchStatus status, LocalDateTime after);

    /** "Next fixture" KPI tile. */
    @EntityGraph(attributePaths = {"teamA", "teamB"})
    Optional<Match> findFirstBySociety_IdAndStatusAndMatchDateAfterOrderByMatchDateAsc(
            Long societyId, MatchStatus status, LocalDateTime after);

    /** Guards team deletion: a team with fixtures cannot be removed silently. */
    boolean existsByTeamA_IdOrTeamB_Id(Long teamAId, Long teamBId);
}