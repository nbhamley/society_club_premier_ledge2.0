package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One admin's vote. The (approval_id, voter_id) unique constraint stops double voting at the DB level. */
@Entity
@Table(name = "admin_approval_votes")
@Getter
@Setter
@NoArgsConstructor
public class AdminApprovalVote extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approval_id", nullable = false)
    private AdminApproval approval;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voter_id", nullable = false)
    private User voter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Vote vote;

    @Column(name = "voted_at", nullable = false)
    private LocalDateTime votedAt;

    private String comment;

    @PrePersist
    protected void stampVoted() {
        if (votedAt == null) {
            votedAt = LocalDateTime.now();
        }
    }
}