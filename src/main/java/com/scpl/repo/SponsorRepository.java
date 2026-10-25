package com.scpl.repo;

import com.scpl.model.Sponsor;
import com.scpl.model.SocietyStatus;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;

public interface SponsorRepository extends SocietyScopedRepository<Sponsor, Long> {

    List<Sponsor> findBySociety_IdOrderByNameAsc(Long societyId);

    List<Sponsor> findBySociety_IdAndActiveTrueOrderByAmountDesc(Long societyId);

    /** Platform home strip. Restricted to ACTIVE societies so draft or suspended tenants never surface. */
    @EntityGraph(attributePaths = "society")
    List<Sponsor> findByActiveTrueAndSociety_StatusOrderByAmountDesc(SocietyStatus status);
}