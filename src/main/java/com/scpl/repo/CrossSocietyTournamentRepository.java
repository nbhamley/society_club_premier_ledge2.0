package com.scpl.repo;

import com.scpl.model.CrossSocietyTournament;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CrossSocietyTournamentRepository extends JpaRepository<CrossSocietyTournament, Long> {

    @EntityGraph(attributePaths = {"hostSociety", "participatingSocieties"})
    List<CrossSocietyTournament> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"hostSociety", "participatingSocieties"})
    Optional<CrossSocietyTournament> findWithSocietiesById(Long id);

    /** Tournaments a society hosts or has been invited to; shown in /society-admin when the flag is on. */
    @Query("select distinct t from CrossSocietyTournament t "
            + "left join t.participatingSocieties p "
            + "where t.hostSociety.id = :societyId or p.id = :societyId "
            + "order by t.createdAt desc")
    List<CrossSocietyTournament> findInvolving(@Param("societyId") Long societyId);
}