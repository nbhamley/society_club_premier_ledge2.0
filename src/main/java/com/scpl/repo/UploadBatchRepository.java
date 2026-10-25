package com.scpl.repo;

import com.scpl.model.BatchStatus;
import com.scpl.model.UploadBatch;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;

import java.time.LocalDateTime;
import java.util.List;

public interface UploadBatchRepository extends SocietyScopedRepository<UploadBatch, Long> {

    @EntityGraph(attributePaths = {"uploadedBy", "approval"})
    List<UploadBatch> findBySociety_IdOrderByCreatedAtDesc(Long societyId);

    /** ImportPollingJob input, oldest first so uploads are processed in arrival order. */
    List<UploadBatch> findByStatusOrderByCreatedAtAsc(BatchStatus status);

    long countBySociety_IdAndStatus(Long societyId, BatchStatus status);

    /**
     * Retention cleanup. Once a batch is REJECTED, the service sets reviewDeadline to the
     * purge date (rejection time + 30 days), so this finds batches whose files can go.
     */
    List<UploadBatch> findByStatusAndReviewDeadlineBefore(BatchStatus status, LocalDateTime cutoff);

    /**
     * Atomic status claim: only one poller run can move a batch UPLOADED to SEGREGATING, so a
     * slow run overlapping the next minute's run cannot process the same file twice.
     * Returns 1 when this caller won the claim.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UploadBatch b set b.status = :to where b.id = :id and b.status = :from")
    int transition(@Param("id") Long id,
                   @Param("from") BatchStatus from,
                   @Param("to") BatchStatus to);

    /** Retention sweep for finished batches (approved, partially approved, failed). */
    List<UploadBatch> findByStatusInAndCreatedAtBefore(Collection<BatchStatus> statuses, LocalDateTime cutoff);
}