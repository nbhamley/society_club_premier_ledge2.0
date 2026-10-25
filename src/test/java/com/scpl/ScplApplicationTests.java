package com.scpl;

import com.scpl.dto.NewAdminRequest;
import com.scpl.dto.SocietyRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.BatchStatus;
import com.scpl.model.PlatformRole;
import com.scpl.model.RegistrationStatus;
import com.scpl.model.Society;
import com.scpl.model.SocietyStatus;
import com.scpl.model.UploadBatch;
import com.scpl.model.User;
import com.scpl.repo.PlatformEventRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.RegistrationRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.UploadBatchRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Acceptance 1 (starts cleanly), 6 (fewer than 3 admins cannot be activated) and 20 (schema is Flyway-managed). */
class ScplApplicationTests extends TenantFixtures {

    @Autowired
    Environment env;
    @Autowired
    Flyway flyway;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SocietyRepository societies;
    @Autowired
    PlayerRepository players;
    @Autowired
    RegistrationRepository registrations;
    @Autowired
    UploadBatchRepository batches;
    @Autowired
    PlatformEventRepository events;

    @Value("${scpl.uploads.dir}")
    String uploadsDir;

    @Test
    void schemaIsOwnedByFlywayAndHibernateOnlyValidates() {
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied().length).isGreaterThanOrEqualTo(2);
        Integer v1 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = 1", Integer.class);
        assertThat(v1).isEqualTo(1);
    }

    @Test
    void demoDataIsSeededForBothSocieties() {
        User platform = users.findByUsernameIgnoreCase("platformadmin").orElseThrow();
        assertThat(platform.getRole()).isEqualTo(PlatformRole.PLATFORM_ADMIN);
        assertThat(platform.getSociety()).isNull();

        for (String slug : List.of("green-valley", "sunrise-heights")) {
            Society s = societies.findBySlug(slug).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SocietyStatus.ACTIVE);
            assertThat(s.getAdminCount()).isEqualTo(3);
            assertThat(players.countBySociety_IdAndActiveTrue(s.getId())).isEqualTo(12);
        }
        Long gv = societies.findBySlug("green-valley").orElseThrow().getId();
        assertThat(registrations.countBySociety_IdAndStatus(gv, RegistrationStatus.PENDING)).isEqualTo(10);
        assertThat(events.count()).isGreaterThanOrEqualTo(6);

        for (String username : List.of("gv.admin1", "gv.admin2", "gv.admin3", "sh.admin1", "gv.player1")) {
            User u = users.findByUsernameIgnoreCase(username).orElseThrow();
            assertThat(u.getPasswordHash()).startsWith("$2").isNotEqualTo("Passw0rd!");
        }
        assertThat(users.findByUsernameIgnoreCase("gv.player1").orElseThrow().getRole()).isEqualTo(PlatformRole.SOCIETY_PLAYER);
        assertThat(users.findByUsernameIgnoreCase("gv.player1").orElseThrow().getPlayerId()).isNotNull();
    }

    @Test
    void sampleImportBatchesAreReadyForReviewWithFilesOnDisk() {
        int found = 0;
        for (String slug : List.of("green-valley", "sunrise-heights")) {
            Society s = societies.findBySlug(slug).orElseThrow();
            List<UploadBatch> ready = batches.findBySociety_IdOrderByCreatedAtDesc(s.getId()).stream()
                    .filter(b -> b.getStatus() == BatchStatus.READY_FOR_REVIEW).toList();
            assertThat(ready).hasSize(slug.equals("green-valley") ? 2 : 1);
            for (UploadBatch b : ready) {
                Path review = Paths.get(uploadsDir).resolve(String.valueOf(s.getId())).resolve(String.valueOf(b.getId()))
                        .resolve("review.csv");
                assertThat(Files.isRegularFile(review)).as("review file for batch %s", b.getId()).isTrue();
                found++;
            }
        }
        assertThat(found).isEqualTo(3);
    }

    @Test
    @Transactional
    void societyWithFewerThanThreeAdminsCannotBeActivated() {
        User platform = users.findByUsernameIgnoreCase("platformadmin").orElseThrow();
        Society tiny = societyService.createWithAdmins(
                new SocietyRequest("Tiny Society", "tiny-society-x", null, null, null, null, null),
                List.of(new NewAdminRequest("tiny.a1", "tiny.a1@x.example", "Tiny One", null, "Passw0rd!Tiny"),
                        new NewAdminRequest("tiny.a2", "tiny.a2@x.example", "Tiny Two", null, "Passw0rd!Tiny")), platform);
        assertThat(tiny.getAdminCount()).isEqualTo(2);
        assertThatThrownBy(() -> societyService.activate(tiny.getId(), platform))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("at least 3");
    }
}