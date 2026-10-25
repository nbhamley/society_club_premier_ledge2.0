package com.scpl.job;

import com.scpl.integration.db.DatabaseDumper;
import com.scpl.model.PlatformRole;
import com.scpl.model.User;
import com.scpl.repo.UserRepository;
import com.scpl.service.AuditActions;
import com.scpl.service.AuditService;
import com.scpl.service.NotificationService;
import com.scpl.util.BackupRetention;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Nightly whole-database dump (§10), then rotation (7 daily + 4 weekly + 3 monthly by default).
 *
 * <p>Rotation runs only after a successful dump, and only deletes files named {yyyy-MM-dd}-full.sqlite.gz / .sql.gz (plus
 * their .sha256 sidecars) directly in the backup directory. The staged/ folder, society backups and anything else are
 * never touched. On failure the platform admins get a bell notification, and the previous backups stay in place.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scpl.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class NightlyBackupJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyBackupJob.class);
    private static final Pattern DUMP_NAME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})-full\\.(?:sqlite|sql)\\.gz$");

    private final DatabaseDumper dumper;
    private final AuditService audit;
    private final NotificationService notifications;
    private final UserRepository users;

    @Value("${scpl.backup.dir:./data/backups}")
    private String backupDir;
    @Value("${scpl.backup.keep-daily:7}")
    private int keepDaily;
    @Value("${scpl.backup.keep-weekly:4}")
    private int keepWeekly;
    @Value("${scpl.backup.keep-monthly:3}")
    private int keepMonthly;

    @Scheduled(cron = "${scpl.backup.nightly-cron:0 30 2 * * *}")
    public void run() {
        runOnce(LocalDate.now());
    }

    /** Separate from run() so a test or an operator can trigger it for a chosen date. */
    public void runOnce(LocalDate today) {
        if (dumper.kind() == null) {
            log.warn("Nightly backup skipped: the configured database type is not supported by the dumper");
            return;
        }
        Path dir = Paths.get(backupDir).toAbsolutePath().normalize();
        DatabaseDumper.Result result;
        try {
            result = dumper.dump(dir, today);
        } catch (IOException | RuntimeException e) {
            log.error("Nightly database backup failed", e);
            alertPlatformAdmins();
            return;
        }
        int removed = 0;
        try {
            removed = rotate(dir);
        } catch (UncheckedIOException | IOException e) {
            // The new backup exists; failing to tidy old ones must not be reported as a failed backup.
            log.error("Backup rotation failed; old dumps were kept", e);
        }
        try {
            audit.record(null, null, AuditActions.NIGHTLY_BACKUP, "DATABASE", null, null,
                    Map.of("file", result.file().getFileName().toString(), "bytes", result.bytes(), "rotatedOut", removed));
        } catch (RuntimeException e) {
            log.warn("Could not write the audit row for the nightly backup", e);
        }
        log.info("Nightly backup complete: {} (rotated out {})", result.file().getFileName(), removed);
    }

    /** Deletes dumps that the retention rule no longer needs. Returns how many dump files were removed. */
    int rotate(Path dir) throws IOException {
        Map<LocalDate, List<Path>> byDate = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Matcher m = DUMP_NAME.matcher(p.getFileName().toString());
                if (!m.matches() || !Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    byDate.computeIfAbsent(LocalDate.parse(m.group(1)), d -> new ArrayList<>()).add(p);
                } catch (DateTimeParseException e) {
                    // Looks like a dump but is not a real date (for example 2026-13-45): leave it alone.
                }
            }
        }
        Set<LocalDate> keep = BackupRetention.keep(byDate.keySet(), keepDaily, keepWeekly, keepMonthly);
        int removed = 0;
        for (Map.Entry<LocalDate, List<Path>> e : byDate.entrySet()) {
            if (keep.contains(e.getKey())) {
                continue;
            }
            for (Path p : e.getValue()) {
                Files.deleteIfExists(p);
                Files.deleteIfExists(p.resolveSibling(p.getFileName() + ".sha256"));
                removed++;
            }
        }
        return removed;
    }

    private void alertPlatformAdmins() {
        try {
            for (User admin : users.findByRoleAndEnabledTrue(PlatformRole.PLATFORM_ADMIN)) {
                notifications.notifyUser(admin, null, "Nightly backup failed",
                        "The database backup did not complete. Check the application log. Earlier backups were kept.",
                        "/platform-admin");
            }
        } catch (RuntimeException e) {
            log.error("Could not notify platform admins about the failed backup", e);
        }
    }
}