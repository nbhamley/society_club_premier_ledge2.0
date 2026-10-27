package com.scpl.repo;

import com.scpl.model.Season;
import com.scpl.model.SeasonStatus;
import com.scpl.model.SocietyStatus;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SeasonRepository extends SocietyScopedRepository<Season, Long> {

    List<Season> findBySociety_IdOrderByCreatedAtDesc(Long societyId);

    List<Season> findBySociety_IdAndStatusIn(Long societyId, Collection<SeasonStatus> statuses);

    /** Quota check for "concurrent seasons" on the Basic/Standard plans. */
    long countBySociety_IdAndStatusIn(Long societyId, Collection<SeasonStatus> statuses);

    /** The season shown on the society's public home. */
    Optional<Season> findFirstBySociety_IdAndStatusInOrderByStartDateDesc(
            Long societyId, Collection<SeasonStatus> statuses);

    List<Season> findBySociety_IdAndRegistrationOpenTrue(Long societyId);

    /** Platform home: seasons that have not finished yet, across every society with the given status. */
    @EntityGraph(attributePaths = "society")
    List<Season> findBySociety_StatusAndStatusInOrderByStartDateAsc(SocietyStatus societyStatus,
                                                                     Collection<SeasonStatus> statuses);

    boolean existsBySociety_IdAndNameIgnoreCase(Long societyId, String name);
}