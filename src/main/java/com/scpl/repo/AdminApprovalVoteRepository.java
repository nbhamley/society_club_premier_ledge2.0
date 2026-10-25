package com.scpl.repo;

import com.scpl.model.AdminApprovalVote;
import com.scpl.model.Vote;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminApprovalVoteRepository extends JpaRepository<AdminApprovalVote, Long> {

    @EntityGraph(attributePaths = "voter")
    List<AdminApprovalVote> findByApproval_IdOrderByVotedAtAsc(Long approvalId);

    boolean existsByApproval_IdAndVoter_Id(Long approvalId, Long voterId);

    long countByApproval_IdAndVote(Long approvalId, Vote vote);
}