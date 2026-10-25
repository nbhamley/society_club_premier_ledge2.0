package com.scpl.repo;

import com.scpl.model.Team;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;

public interface TeamRepository extends SocietyScopedRepository<Team, Long> {

    @EntityGraph(attributePaths = "captain")
    List<Team> findBySeason_IdOrderByNameAsc(Long seasonId);

    @EntityGraph(attributePaths = {"captain", "season"})
    List<Team> findBySociety_IdOrderByNameAsc(Long societyId);

    /** Quota check: team limits on each plan apply per season. */
    long countBySeason_Id(Long seasonId);

    boolean existsBySeason_IdAndNameIgnoreCase(Long seasonId, String name);
}