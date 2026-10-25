package com.scpl;

import com.scpl.dto.BackupView;
import com.scpl.dto.ImportRows.FileDownload;
import com.scpl.dto.NewAdminRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.exception.TenantAccessException;
import com.scpl.model.BackupStatus;
import com.scpl.model.PlatformRole;
import com.scpl.model.Society;
import com.scpl.model.SocietyStatus;
import com.scpl.model.Subscription;
import com.scpl.model.SubscriptionStatus;
import com.scpl.model.User;
import com.scpl.repo.AuditLogRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.SubscriptionRepository;
import com.scpl.service.AuditActions;
import com.scpl.service.BackupCrypto;
import com.scpl.service.BackupService;
import com.scpl.service.RestoreService;
import com.scpl.service.SubscriptionService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance 13 and 14: encrypted export, restore as the only way back in, past-due societies keep their export right.
 * Ordered because the rate-limit test (last but one) uses up Sunrise Heights' daily allowance.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupServiceTest extends IntegrationTestBase {

    @Autowired
    BackupService backups;
    @Autowired
    RestoreService restore;
    @Autowired
    BackupCrypto crypto;
    @Autowired
    com.scpl.repo.UserRepository users;
    @Autowired
    SocietyRepository societies;
    @Autowired
    PlayerRepository players;
    @Autowired
    SubscriptionRepository subscriptions;
    @Autowired
    SubscriptionService subscriptionService;
    @Autowired
    AuditLogRepository auditLogs;

    private static byte[] gvBackupBytes;
    private static String gvBackupName;

    private User admin(String username) {
        return users.findByUsernameIgnoreCase(username).orElseThrow();
    }

    @Test
    @Order(1)
    void aBackupIsEncryptedCompleteAndContainsOnlyThatSociety() throws Exception {
        User gv = admin("gv.admin1");
        Long gvId = gv.getSocietyId();
        BackupView view = backups.generate(gv, gvId);

        assertThat(view.status()).isEqualTo(BackupStatus.READY);
        assertThat(view.fileName()).matches("scpl_backup_" + gvId + "_\\d{14}\\.scplenc");
        assertThat(view.algorithm()).contains("AES-256-GCM");

        FileDownload file = backups.download(gv, gvId, view.id());
        byte[] bytes = Files.readAllBytes(file.path());
        gvBackupBytes = bytes;
        gvBackupName = view.fileName();
        assertThat(BackupCrypto.sha256Hex(bytes)).isEqualTo(view.sha256());
        assertThat(new String(bytes, ISO_8859_1)).doesNotContain("Green Valley");     // not readable without the key

        BackupCrypto.Opened opened = crypto.open(bytes);
        assertThat(opened.societyId()).isEqualTo(gvId);
        String json = new String(BackupCrypto.gunzip(opened.gzipped(), 50L * 1024 * 1024), UTF_8);
        assertThat(json).contains("Green Valley").contains("Valley Vipers").contains("formatVersion")
                .doesNotContain("Sunrise Heights")
                .doesNotContain("passwordHash").doesNotContain("$2a$").doesNotContain("$2b$");

        assertThat(backups.list(gv, gvId).get(0).status()).isEqualTo(BackupStatus.DOWNLOADED);
        assertThat(auditLogs.countBySocietyIdAndAction(gvId, AuditActions.BACKUP_GENERATED)).isGreaterThanOrEqualTo(1);

        // another society's admin can neither download it nor generate one for this society
        User sh = admin("sh.admin1");
        assertThatThrownBy(() -> backups.download(sh, sh.getSocietyId(), view.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> backups.generate(sh, gvId)).isInstanceOf(TenantAccessException.class);
    }

    @Test
    @Order(2)
    void restoreCreatesANewDraftSocietyAndRefusesTamperedOrUnauthorisedFiles() {
        assertThat(gvBackupBytes).as("test 1 must run first").isNotNull();
        User platform = admin("platformadmin");
        String tag = UUID.randomUUID().toString().substring(0, 6);
        List<NewAdminRequest> founders = List.of(1, 2, 3).stream().map(i -> new NewAdminRequest("rs" + tag + ".a" + i,
                "rs" + tag + ".a" + i + "@restore.example", "Restored Admin " + i, null, "Passw0rd!Restore")).toList();
        long societiesBefore = societies.count();

        byte[] tampered = gvBackupBytes.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> restore.restore(platform, new MockMultipartFile("file", gvBackupName, "application/octet-stream", tampered),
                "Tampered", founders)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> restore.restore(admin("gv.admin1"), new MockMultipartFile("file", gvBackupName,
                "application/octet-stream", gvBackupBytes), "Nope", founders)).isInstanceOf(TenantAccessException.class);
        assertThatThrownBy(() -> restore.restore(platform, new MockMultipartFile("file", gvBackupName, "application/octet-stream",
                gvBackupBytes), "Too few admins", founders.subList(0, 2))).isInstanceOf(BusinessRuleException.class);
        assertThat(societies.count()).isEqualTo(societiesBefore);

        RestoreService.Result result = restore.restore(platform, new MockMultipartFile("file", gvBackupName,
                "application/octet-stream", gvBackupBytes), "Green Valley Restored", founders);

        assertThat(societies.count()).isEqualTo(societiesBefore + 1);
        assertThat(result.counts()).containsEntry("players", 12).containsEntry("teams", 4);
        Society restored = societies.findById(result.societyId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(SocietyStatus.DRAFT);            // never goes live on its own
        assertThat(restored.getSlug()).isNotEqualTo("green-valley");                // never overwrites
        assertThat(players.countBySociety_IdAndActiveTrue(restored.getId())).isEqualTo(12);
        assertThat(users.findBySociety_IdAndRoleOrderByFullNameAsc(restored.getId(), PlatformRole.SOCIETY_ADMIN)).hasSize(3);
        assertThat(users.countBySociety_IdAndRole(restored.getId(), PlatformRole.SOCIETY_PLAYER)).isZero();   // no old logins copied
        assertThat(subscriptionService.current(restored.getId())).isPresent();
        assertThat(subscriptionService.current(restored.getId()).orElseThrow().getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        // the original is untouched
        assertThat(societies.findBySlug("green-valley").orElseThrow().getStatus()).isEqualTo(SocietyStatus.ACTIVE);
    }

    @Test
    @Order(3)
    void aPastDueSocietyCanStillTakeItsFinalBackup() throws Exception {
        User sh = admin("sh.admin1");
        Long shId = sh.getSocietyId();
        Subscription sub = subscriptions.findFirstBySociety_IdOrderByCreatedAtDesc(shId).orElseThrow();
        SubscriptionStatus originalStatus = sub.getStatus();
        LocalDateTime originalPastDue = sub.getPastDueSince();
        try {
            sub.setStatus(SubscriptionStatus.PAST_DUE);
            sub.setPastDueSince(LocalDateTime.now().minusDays(40));
            subscriptions.save(sub);
            assertThat(subscriptionService.isReadOnly(shId)).isTrue();

            BackupView view = backups.generate(sh, shId);
            assertThat(view.status()).isEqualTo(BackupStatus.READY);
            assertThat(Files.size(backups.download(sh, shId, view.id()).path())).isGreaterThan(0);
        } finally {
            Subscription restore = subscriptions.findFirstBySociety_IdOrderByCreatedAtDesc(shId).orElseThrow();
            restore.setStatus(originalStatus);
            restore.setPastDueSince(originalPastDue);
            subscriptions.save(restore);
        }
    }

    @Test
    @Order(4)
    void backupsAreRateLimitedPerDay() {
        User sh = admin("sh.admin1");
        Long shId = sh.getSocietyId();
        int created = 0;
        BusinessRuleException refused = null;
        for (int i = 0; i < 5 && refused == null; i++) {
            try {
                backups.generate(sh, shId);
                created++;
            } catch (BusinessRuleException e) {
                refused = e;
            }
        }
        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("per day");
        assertThat(created).isLessThanOrEqualTo(3);
    }

    @Test
    @Order(5)
    void expiredBackupsAreDeletedWithTheirFiles() {
        User gv = admin("gv.admin1");
        Path file = backups.download(gv, gv.getSocietyId(), backups.list(gv, gv.getSocietyId()).get(0).id()).path();
        assertThat(file).exists();
        int purged = backups.purgeExpired(LocalDateTime.now().plusDays(31));
        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(file).doesNotExist();
        assertThat(backups.list(gv, gv.getSocietyId())).isEmpty();
    }
}