package com.scpl.repo;

import com.scpl.model.Registration;
import com.scpl.model.RegistrationStatus;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Collection;
import java.util.List;

public interface RegistrationRepository extends SocietyScopedRepository<Registration, Long> {

    @EntityGraph(attributePaths = "season")
    List<Registration> findBySociety_IdOrderByCreatedAtDesc(Long societyId);

    @EntityGraph(attributePaths = "season")
    List<Registration> findBySociety_IdAndStatusOrderByCreatedAtDesc(Long societyId, RegistrationStatus status);

    List<Registration> findBySeason_IdOrderByCreatedAtDesc(Long seasonId);

    /** Bulk actions: restricting by society means a forged id from another tenant is simply ignored. */
    @EntityGraph(attributePaths = "season")
    List<Registration> findByIdInAndSociety_Id(Collection<Long> ids, Long societyId);

    /** Blocks a second sign-up with the same email in a season unless the first was rejected. */
    boolean existsBySeason_IdAndEmailIgnoreCaseAndStatusIn(
            Long seasonId, String email, Collection<RegistrationStatus> statuses);

    List<Registration> findByApproval_Id(Long approvalId);

    long countBySociety_IdAndStatus(Long societyId, RegistrationStatus status);
}