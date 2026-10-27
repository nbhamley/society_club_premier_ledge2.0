package com.scpl.repo;

import com.scpl.model.Player;
import com.scpl.model.PlayerRole;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends SocietyScopedRepository<Player, Long> {

    /**
     * Closed projection for any view that must never carry PII: platform admin and
     * cross-society rosters (§6, §14). Spring Data selects only these columns, so email,
     * phone, DOB and address are never even read from the database.
     */
    interface PlayerRosterRow {
        Long getId();

        String getName();

        PlayerRole getRole();

        Integer getMatchesPlayed();

        Integer getRuns();

        Integer getWickets();

        long countByActiveTrue();
    }

    @EntityGraph(attributePaths = "team")
    List<Player> findBySociety_IdOrderByNameAsc(Long societyId);

    @EntityGraph(attributePaths = "team")
    List<Player> findBySociety_IdAndActiveTrueOrderByNameAsc(Long societyId);

    List<Player> findByTeam_IdOrderByNameAsc(Long teamId);

    List<Player> findBySociety_IdAndTeamIsNull(Long societyId);

    /** Public player page: society and team are fetched, the caller maps to a PII-free view. */
    @EntityGraph(attributePaths = {"team", "society"})
    Optional<Player> findWithDetailsByIdAndSociety_Id(Long id, Long societyId);

    List<PlayerRosterRow> findRosterBySociety_IdAndActiveTrueOrderByNameAsc(Long societyId);

    // ---- import conflict checks and quotas ----

    boolean existsBySociety_IdAndEmailIgnoreCase(Long societyId, String email);

    boolean existsBySociety_IdAndNameIgnoreCase(Long societyId, String name);

    Optional<Player> findBySociety_IdAndEmailIgnoreCase(Long societyId, String email);

    long countBySociety_IdAndActiveTrue(Long societyId);

    long countByActiveTrue();
}