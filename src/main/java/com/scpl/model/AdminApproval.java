package com.scpl.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One governed operation awaiting votes from the society's admins.
 *
 * <p>The requester can never vote, so only (eligibleVoters - 1) people can cast votes.
 * With the default threshold of 3 and a society of exactly 3 admins that pool is 2, so
 * AdminApprovalService clamps approvalsRequired to min(default, pool) when it raises the
 * request. The helper methods below work off the stored values only, so the clamp is
 * decided once and the outcome never shifts if admins are added later.
 */
@Entity
@Table(name = "admin_approvals")
@Getter
@Setter
@NoArgsConstructor
public class AdminApproval extends BaseEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    /** One of the ApprovalEntityType constants. */
    @Column(name = "entity_type", nullable = false)
    private String entityType;

    /** Polymorphic reference; null for REGISTRATION_BATCH, whose ids live in entityIds. */
    @Column(name = "entity_id")
    private Long entityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @Column(name = "approvals_required", nullable = false)
    private Integer approvalsRequired = 3;

    /** Snapshot of society.adminCount when the request was raised. */
    @Column(name = "eligible_voters", nullable = false)
    private Integer eligibleVoters = 3;

    private String notes;

    /** JSON context consumed by performApprovedAction (for example the invited admin's details). */
    private String payload;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_notes")
    private String decisionNotes;

    /** Wrapped ids for REGISTRATION_BATCH approvals (table admin_approval_items). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "admin_approval_items", joinColumns = @JoinColumn(name = "approval_id"))
    @Column(name = "entity_id", nullable = false)
    private Set<Long> entityIds = new HashSet<>();

    @OneToMany(mappedBy = "approval", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AdminApprovalVote> votes = new ArrayList<>();

    @PrePersist
    protected void stampRequested() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
    }

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }

    public long countVotes(Vote vote) {
        return votes.stream().filter(v -> v.getVote() == vote).count();
    }

    /** People who may vote: everyone except the requester. */
    public int votingPool() {
        return Math.max(eligibleVoters - 1, 0);
    }

    public boolean hasVoted(User user) {
        return user != null && votes.stream().anyMatch(v -> user.equals(v.getVoter()));
    }

    public boolean isThresholdMet() {
        return countVotes(Vote.APPROVE) >= approvalsRequired;
    }

    /** True once so many REJECT votes exist that the APPROVE threshold can no longer be reached. */
    public boolean isThresholdUnreachable() {
        return votingPool() - countVotes(Vote.REJECT) < approvalsRequired;
    }
}