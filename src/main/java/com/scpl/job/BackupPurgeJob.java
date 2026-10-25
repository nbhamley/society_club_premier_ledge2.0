package com.scpl.job;

import com.scpl.service.BackupService;
import com.scpl.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Retention (§10): society backups older than 30 days, and import files (which contain PII) after their retention
 * period. Each step runs on its own, so a failure in one never skips the other. This job does NOT touch the nightly
 * database dumps: NightlyBackupJob rotates those.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scpl.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class BackupPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(BackupPurgeJob.class);

    private final BackupService backups;
    private final ImportService imports;

    @Scheduled(cron = "${scpl.maintenance.purge-cron:0 30 3 * * *}")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        step("Society backups purged", () -> backups.purgeExpired(now));
        step("Import files purged", () -> imports.purgeExpired(now));
    }

    private static void step(String what, Supplier<Object> action) {
        try {
            log.info("{}: {}", what, action.get());
        } catch (RuntimeException e) {
            log.error("{} failed", what, e);
        }
    }
}