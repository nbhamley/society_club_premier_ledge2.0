package com.scpl.job;

import com.scpl.integration.db.DatabaseDumper;
import com.scpl.model.PlatformRole;
import com.scpl.model.User;
import com.scpl.repo.UserRepository;
import com.scpl.service.AuditActions;
import com.scpl.service.AuditService;
import com.scpl.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NightlyBackupJobTest {

    @TempDir
    Path dir;

    private DatabaseDumper dumper;
    private AuditService audit;
    private NotificationService notifications;
    private UserRepository users;
    private NightlyBackupJob job;

    @BeforeEach
    void setUp() {
        dumper = mock(DatabaseDumper.class);
        audit = mock(AuditService.class);
        notifications = mock(NotificationService.class);
        users = mock(UserRepository.class);
        job = new NightlyBackupJob(dumper, audit, notifications, users);
        ReflectionTestUtils.setField(job, "backupDir", dir.toString());
        ReflectionTestUtils.setField(job, "keepDaily", 2);
        ReflectionTestUtils.setField(job, "keepWeekly", 0);
        ReflectionTestUtils.setField(job, "keepMonthly", 0);
    }

    private void dump(String date) throws IOException {
        Files.writeString(dir.resolve(date + "-full.sqlite.gz"), "x");
        Files.writeString(dir.resolve(date + "-full.sqlite.gz.sha256"), "abc");
    }

    @Test
    void rotationRemovesOnlyDumpsTheRetentionRuleNoLongerNeeds() throws IOException {
        for (String d : List.of("2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25")) {
            dump(d);
        }
        Files.writeString(dir.resolve("notes.txt"), "keep");
        Files.writeString(dir.resolve("2026-13-45-full.sqlite.gz"), "not a real date");
        Files.createDirectories(dir.resolve("staged"));
        Files.writeString(dir.resolve("staged").resolve("dump-1.sqlite.gz"), "staged");

        int removed = job.rotate(dir);

        assertThat(removed).isEqualTo(4);
        assertThat(dir.resolve("2026-10-25-full.sqlite.gz")).exists();
        assertThat(dir.resolve("2026-10-24-full.sqlite.gz")).exists();
        assertThat(dir.resolve("2026-10-24-full.sqlite.gz.sha256")).exists();
        assertThat(dir.resolve("2026-10-23-full.sqlite.gz")).doesNotExist();
        assertThat(dir.resolve("2026-10-23-full.sqlite.gz.sha256")).doesNotExist();
        assertThat(dir.resolve("2026-10-20-full.sqlite.gz")).doesNotExist();
        assertThat(dir.resolve("notes.txt")).exists();
        assertThat(dir.resolve("2026-13-45-full.sqlite.gz")).exists();
        assertThat(dir.resolve("staged").resolve("dump-1.sqlite.gz")).exists();
    }

    @Test
    void aFailedDumpAlertsPlatformAdminsAndKeepsEveryOldBackup() throws Exception {
        dump("2026-10-01");
        when(dumper.kind()).thenReturn(DatabaseDumper.Kind.SQLITE);
        when(dumper.dump(any(Path.class), any(LocalDate.class))).thenThrow(new IOException("disk full"));
        User admin = new User();
        admin.setId(9L);
        when(users.findByRoleAndEnabledTrue(PlatformRole.PLATFORM_ADMIN)).thenReturn(List.of(admin));

        job.runOnce(LocalDate.of(2026, 10, 25));

        verify(notifications).notifyUser(eq(admin), isNull(), eq("Nightly backup failed"), anyString(), anyString());
        assertThat(dir.resolve("2026-10-01-full.sqlite.gz")).exists();
        verifyNoInteractions(audit);
    }

    @Test
    void aSuccessfulDumpIsAuditedAndOlderOnesRotatedOut() throws Exception {
        dump("2026-10-20");
        dump("2026-10-21");
        dump("2026-10-22");
        when(dumper.kind()).thenReturn(DatabaseDumper.Kind.SQLITE);
        when(dumper.dump(any(Path.class), any(LocalDate.class))).thenAnswer(inv -> {
            dump("2026-10-25");
            return new DatabaseDumper.Result(dir.resolve("2026-10-25-full.sqlite.gz"), 1, "sha", DatabaseDumper.Kind.SQLITE);
        });

        job.runOnce(LocalDate.of(2026, 10, 25));

        assertThat(dir.resolve("2026-10-25-full.sqlite.gz")).exists();
        assertThat(dir.resolve("2026-10-22-full.sqlite.gz")).exists();
        assertThat(dir.resolve("2026-10-21-full.sqlite.gz")).doesNotExist();
        assertThat(dir.resolve("2026-10-20-full.sqlite.gz")).doesNotExist();
        verify(audit).record(isNull(), isNull(), eq(AuditActions.NIGHTLY_BACKUP), eq("DATABASE"), isNull(), isNull(), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void anUnsupportedDatabaseIsSkippedQuietly() throws Exception {
        when(dumper.kind()).thenReturn(null);
        job.runOnce(LocalDate.of(2026, 10, 25));
        verify(dumper, never()).dump(any(), any());
    }
}