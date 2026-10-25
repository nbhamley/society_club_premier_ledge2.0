package com.scpl.repo;

import com.scpl.model.AdminApproval;
import com.scpl.model.ApprovalStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AdminApprovalRepository extends SocietyScopedRepository<AdminApproval, Long> {

    /**
     * Votes and voters are fetched because the approvals page shows who voted what. The
     * entityIds collection stays lazy; AdminApprovalService reads it inside its transaction.
     */
    @EntityGraph(attributePaths = {"requestedBy", "votes", "votes.voter"})
    Optional<AdminApproval> findDetailedById(Long id);

    @EntityGraph(attributePaths = {"requestedBy", "votes", "votes.voter"})
    Optional<AdminApproval> findDetailedByIdAndSociety_Id(Long id, Long societyId);

    @EntityGraph(attributePaths = {"requestedBy", "votes", "votes.voter"})
    List<AdminApproval> findBySociety_IdAndStatusOrderByRequestedAtDesc(Long societyId, ApprovalStatus status);

    @EntityGraph(attributePaths = {"requestedBy", "votes", "votes.voter"})
    List<AdminApproval> findBySociety_IdOrderByRequestedAtDesc(Long societyId);

    /**
     * Items this admin can still act on: pending, raised by someone else, not yet voted on.
     * Drives the bell badge and the "needs your vote" highlight (§6: requester never votes).
     */
    @Query("select a from AdminApproval a "
            + "where a.society.id = :societyId and a.status = :status "
            + "and a.requestedBy.id <> :userId "
            + "and not exists (select 1 from AdminApprovalVote v where v.approval = a and v.voter.id = :userId) "
            + "order by a.requestedAt desc")
    List<AdminApproval> findAwaitingVoteFrom(@Param("societyId") Long societyId,
                                             @Param("status") ApprovalStatus status,
                                             @Param("userId") Long userId);

    long countBySociety_IdAndStatus(Long societyId, ApprovalStatus status);

    long countByStatus(ApprovalStatus status);

    /** Duplicate guard: no second open approval for the same entity. */
    boolean existsBySociety_IdAndEntityTypeAndEntityIdAndStatus(
            Long societyId, String entityType, Long entityId, ApprovalStatus status);

    Optional<AdminApproval> findFirstBySociety_IdAndEntityTypeAndEntityIdOrderByRequestedAtDesc(
            Long societyId, String entityType, Long entityId);
}