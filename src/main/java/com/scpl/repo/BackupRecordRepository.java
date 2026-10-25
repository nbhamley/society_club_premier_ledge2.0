package com.scpl.repo;

import com.scpl.model.BackupRecord;
import com.scpl.model.BackupStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BackupRecordRepository extends SocietyScopedRepository<BackupRecord, Long> {

    List<BackupRecord> findBySociety_IdOrderByCreatedAtDesc(Long societyId);

    Optional<BackupRecord> findFirstBySociety_IdOrderByCreatedAtDesc(Long societyId);

    /**
     * Counts backups taken since a point in time. Used for the "one final backup" allowance
     * for PAST_DUE societies and as a simple rate limit on generating new ones.
     */
    long countBySociety_IdAndCreatedAtAfter(Long societyId, LocalDateTime after);

    /** BackupPurgeJob input: expired records whose files should be deleted. */
    List<BackupRecord> findByExpiresAtBeforeAndStatusIn(LocalDateTime cutoff, Collection<BackupStatus> statuses);

    /** RestoreService cross-checks an uploaded file's checksum against the platform's own record. */
    Optional<BackupRecord> findFirstBySociety_IdAndFileName(Long societyId, String fileName);
}