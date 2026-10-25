package com.scpl.repo;

import com.scpl.model.Award;
import com.scpl.model.SocietyStatus;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface AwardRepository extends SocietyScopedRepository<Award, Long> {

    @EntityGraph(attributePaths = {"player", "team", "season"})
    List<Award> findBySociety_IdOrderByCreatedAtDesc(Long societyId);

    @EntityGraph(attributePaths = {"player", "team"})
    List<Award> findBySeason_IdOrderByCreatedAtDesc(Long seasonId);

    List<Award> findByPlayer_Id(Long playerId);

    /** Award detail page and share cards need the owning society for the public URL. */
    @EntityGraph(attributePaths = {"player", "team", "season", "society"})
    Optional<Award> findWithDetailsById(Long id);

    /** "Recent winners" strip on the platform home, active societies only. */
    @EntityGraph(attributePaths = {"player", "team", "society"})
    List<Award> findTop10BySociety_StatusOrderByCreatedAtDesc(SocietyStatus status);
}