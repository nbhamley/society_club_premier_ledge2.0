package com.scpl.service;

import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AdminApproval;
import com.scpl.model.AdminApprovalVote;
import com.scpl.model.ApprovalEntityType;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.Registration;
import com.scpl.model.Society;
import com.scpl.model.User;
import com.scpl.model.Vote;
import com.scpl.repo.AdminApprovalRepository;
import com.scpl.repo.RegistrationRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Admin approval engine (§6).
 *
 * <p>Threshold, fixed per request when it is raised (see {@link #requiredApprovals}):
 * <ul>
 *   <li>ADMIN_INVITE: min(configured admin-invite threshold, eligibleVoters - 1) distinct admins. The requester
 *       can never vote, so a 3-admin society has only 2 possible voters and could never reach 3.</li>
 *   <li>Everything else (registrations, drops, imports, season deletes, admin removals, tournaments):
 *       a single approval from any admin other than the requester.</li>
 * </ul>
 * Votes are counted per distinct admin: a second vote by the same admin is refused, and the
 * (approval_id, voter_id) unique constraint backs that up.
 *
 * <p>Concurrency: "count votes, then decide" must not interleave for one approval. A striped
 * in-JVM lock wraps a whole programmatic transaction (the lock has to cover the commit, which
 * a lock inside an @Transactional method would not). This is correct for one application
 * node. For several nodes, replace it with an UPDATE ... WHERE status = 'PENDING' compare-and-set.
 */
@Service
public class AdminApprovalService {

    private static final int LOCK_STRIPES = 32;
    /** Approvals needed for every type except ADMIN_INVITE. */
    static final int SINGLE_APPROVAL = 1;

    private final AdminApprovalRepository approvals;
    private final SocietyRepository societies;
    private final UserRepository users;
    private final RegistrationRepository registrations;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;
    private final int adminInviteRequired;

    private final ReentrantLock[] locks = IntStream.range(0, LOCK_STRIPES)
            .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    public AdminApprovalService(AdminApprovalRepository approvals, SocietyRepository societies,
                                UserRepository users, RegistrationRepository registrations,
                                NotificationService notifications, AuditService audit,
                                ApplicationEventPublisher publisher, PlatformTransactionManager txManager,
                                @Value("${scpl.approval.admin-invite-required:3}") int adminInviteRequired) {
        this.approvals = approvals;
        this.societies = societies;
        this.users = users;
        this.registrations = registrations;
        this.notifications = notifications;
        this.audit = audit;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(txManager);
        if (adminInviteRequired < 1) {
            throw new IllegalArgumentException("scpl.approval.admin-invite-required must be at least 1");
        }
        this.adminInviteRequired = adminInviteRequired;
    }

    /**
     * Approvals needed for a request of this type when {@code pool} admins (everyone except the
     * requester) can vote. Adding an admin needs the configured number of distinct admins, capped at the
     * pool so a small society can still decide; every other type needs one approval.
     */
    public int requiredApprovals(String entityType, int pool) {
        if (ApprovalEntityType.ADMIN_INVITE.equals(entityType)) {
            return Math.min(adminInviteRequired, pool);
        }
        return SINGLE_APPROVAL;
    }

    // ------------------------------------------------------------------ raise

    /**
     * Opens an approval. Joins the caller's transaction, so the held operation (for example
     * flipping registrations to AWAITING_APPROVAL) and the approval row commit together.
     *
     * @param entityId  single polymorphic target, or null for batches
     * @param entityIds wrapped ids for REGISTRATION_BATCH, otherwise null
     */
    @Transactional
    public AdminApproval raise(User requester, Long societyId, String entityType, Long entityId,
                               Collection<Long> entityIds, String notes, String payload) {
        Tenancy.requireAdminOf(requester, societyId);
        if (!ApprovalEntityType.ALL.contains(entityType)) {
            throw new BusinessRuleException("Unknown approval type: " + entityType);
        }
        if (entityId != null && approvals.existsBySociety_IdAndEntityTypeAndEntityIdAndStatus(
                societyId, entityType, entityId, ApprovalStatus.PENDING)) {
            throw new BusinessRuleException("An approval for this item is already waiting for votes");
        }
        Society society = societies.findById(societyId)
                .orElseThrow(() -> new NotFoundException("Society not found"));

        int eligible = society.getAdminCount();
        int pool = eligible - 1;
        if (pool < 1) {
            throw new BusinessRuleException("At least two admins are needed before an approval can be raised");
        }

        AdminApproval a = new AdminApproval();
        a.setSociety(society);
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setRequestedBy(requester);
        a.setRequestedAt(LocalDateTime.now());
        a.setEligibleVoters(eligible);
        a.setApprovalsRequired(requiredApprovals(entityType, pool));
        a.setNotes(notes);
        a.setPayload(payload);
        if (entityIds != null) {
            a.getEntityIds().addAll(entityIds);
        }
        a = approvals.save(a);

        notifications.notifyAdmins(societyId, requester.getId(), "Approval needed", notes,
                "/society-admin/approvals");
        audit.record(requester, societyId, AuditActions.APPROVAL_RAISED, entityType, a.getId(), null,
                Map.of("type", entityType, "required", a.getApprovalsRequired(), "eligible", eligible));
        return a;
    }

    // ------------------------------------------------------------------ vote

    public AdminApproval castVote(User voter, Long approvalId, Vote vote, String comment) {
        return locked(approvalId, () -> doVote(voter, approvalId, vote, comment));
    }

    private AdminApproval doVote(User voter, Long approvalId, Vote vote, String comment) {
        AdminApproval a = approvals.findDetailedById(approvalId)
                .orElseThrow(() -> new NotFoundException("Approval not found"));
        Tenancy.requireAdminOf(voter, a.getSocietyId());

        if (!a.isPending()) {
            throw new BusinessRuleException("This approval is already "
                    + a.getStatus().getLabel().toLowerCase(Locale.ROOT));
        }
        if (a.getRequestedBy().getId().equals(voter.getId())) {
            throw new BusinessRuleException("The admin who raised an approval cannot vote on it");
        }
        if (a.getVotes().stream().anyMatch(v -> v.getVoter().getId().equals(voter.getId()))) {
            throw new BusinessRuleException("You have already voted on this approval");
        }
        assertNoConflictOfInterest(a, voter);

        AdminApprovalVote v = new AdminApprovalVote();
        v.setApproval(a);
        v.setVoter(users.getReferenceById(voter.getId()));
        v.setVote(vote);
        v.setVotedAt(LocalDateTime.now());
        v.setComment(comment);
        a.getVotes().add(v);
        try {
            approvals.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            // The (approval_id, voter_id) unique constraint is the last line of defence.
            throw new BusinessRuleException("You have already voted on this approval");
        }
        audit.record(voter, a.getSocietyId(), AuditActions.APPROVAL_VOTE_CAST, a.getEntityType(), a.getId(),
                null, Map.of("vote", vote.name()));

        if (a.isThresholdMet()) {
            decide(a, ApprovalStatus.APPROVED, voter);
        } else if (a.isThresholdUnreachable()) {
            decide(a, ApprovalStatus.REJECTED, voter);
        }
        return a;
    }

    /**
     * An admin who is the subject of a registration approval must not decide it (§6: an
     * admin-as-player cannot approve their own entry). Limitation: such an admin also shrinks
     * the real voter pool, so a small society can end up with an approval that can no longer
     * pass. It then stays PENDING until the requester withdraws it.
     */
    private void assertNoConflictOfInterest(AdminApproval a, User voter) {
        if (!ApprovalEntityType.REGISTRATION.equals(a.getEntityType())
                && !ApprovalEntityType.REGISTRATION_BATCH.equals(a.getEntityType())) {
            return;
        }
        for (Registration r : registrations.findByApproval_Id(a.getId())) {
            boolean sameEmail = r.getEmail() != null && r.getEmail().equalsIgnoreCase(voter.getEmail());
            boolean samePlayer = voter.getPlayerId() != null && r.getPlayer() != null
                    && voter.getPlayerId().equals(r.getPlayer().getId());
            if (sameEmail || samePlayer) {
                throw new BusinessRuleException("You are one of the people in this approval, so you cannot vote "
                        + "on it. The other admins must decide.");
            }
        }
    }

    private void decide(AdminApproval a, ApprovalStatus outcome, User decidedBy) {
        a.setStatus(outcome);
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionNotes(String.format(Locale.ROOT, "%d approve / %d reject (needed %d)",
                a.countVotes(Vote.APPROVE), a.countVotes(Vote.REJECT), a.getApprovalsRequired()));
        approvals.save(a);

        boolean approved = outcome == ApprovalStatus.APPROVED;
        audit.record(decidedBy, a.getSocietyId(),
                approved ? AuditActions.APPROVAL_APPROVED : AuditActions.APPROVAL_REJECTED,
                a.getEntityType(), a.getId(), null, Map.of("result", outcome.name(), "detail", a.getDecisionNotes()));
        notifications.notifyUser(a.getRequestedBy(), a.getSocietyId(),
                "Approval " + outcome.getLabel().toLowerCase(Locale.ROOT), a.getNotes(), "/society-admin/approvals");

        if (approved) {
            performApprovedAction(a, decidedBy);
        } else {
            publisher.publishEvent(new ApprovalDecidedEvent(a, decidedBy));
        }
    }

    /**
     * Single dispatch point for approved operations. It publishes an event instead of calling
     * the domain services directly, because those services depend on this one to raise
     * approvals and a direct call would be a circular dependency. Listeners run in this
     * transaction: if one throws, the deciding vote rolls back and the approval stays PENDING.
     */
    public void performApprovedAction(AdminApproval approval, User decidedBy) {
        if (approval.getStatus() != ApprovalStatus.APPROVED) {
            throw new IllegalStateException("Approval " + approval.getId() + " is not approved");
        }
        publisher.publishEvent(new ApprovalDecidedEvent(approval, decidedBy));
    }

    // ------------------------------------------------------------------ withdraw

    public AdminApproval withdraw(User actor, Long approvalId) {
        return locked(approvalId, () -> {
            AdminApproval a = approvals.findDetailedById(approvalId)
                    .orElseThrow(() -> new NotFoundException("Approval not found"));
            Tenancy.requireAdminOf(actor, a.getSocietyId());
            if (!a.getRequestedBy().getId().equals(actor.getId())) {
                throw new BusinessRuleException("Only the admin who raised an approval can withdraw it");
            }
            if (!a.isPending()) {
                throw new BusinessRuleException("Only a pending approval can be withdrawn");
            }
            a.setStatus(ApprovalStatus.WITHDRAWN);
            a.setDecidedAt(LocalDateTime.now());
            approvals.save(a);
            audit.record(actor, a.getSocietyId(), AuditActions.APPROVAL_WITHDRAWN, a.getEntityType(), a.getId(), null, null);
            publisher.publishEvent(new ApprovalDecidedEvent(a, actor));
            return a;
        });
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public AdminApproval get(Long approvalId, Long societyId) {
        return approvals.findDetailedByIdAndSociety_Id(approvalId, societyId)
                .orElseThrow(() -> new NotFoundException("Approval not found"));
    }

    @Transactional(readOnly = true)
    public List<AdminApproval> pending(Long societyId) {
        return approvals.findBySociety_IdAndStatusOrderByRequestedAtDesc(societyId, ApprovalStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<AdminApproval> history(Long societyId) {
        return approvals.findBySociety_IdOrderByRequestedAtDesc(societyId);
    }

    /** Pending items this admin can still vote on (bell badge and "needs your vote" highlight). */
    @Transactional(readOnly = true)
    public List<AdminApproval> awaitingVoteFrom(User admin) {
        return approvals.findAwaitingVoteFrom(admin.getSocietyId(), ApprovalStatus.PENDING, admin.getId());
    }

    @Transactional(readOnly = true)
    public long pendingCount(Long societyId) {
        return approvals.countBySociety_IdAndStatus(societyId, ApprovalStatus.PENDING);
    }

    // ------------------------------------------------------------------ locking

    private <T> T locked(Long approvalId, Supplier<T> work) {
        ReentrantLock lock = locks[(int) Math.floorMod(approvalId, (long) LOCK_STRIPES)];
        lock.lock();
        try {
            return tx.execute(status -> work.get());
        } finally {
            lock.unlock();
        }
    }
}