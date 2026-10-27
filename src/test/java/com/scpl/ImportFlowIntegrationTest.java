package com.scpl;

import com.scpl.dto.ImportRows.BatchView;
import com.scpl.dto.ImportRows.ReviewPreview;
import com.scpl.dto.ImportRows.ReviewRow;
import com.scpl.dto.PlayerRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.TenantAccessException;
import com.scpl.model.AdminApproval;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.BatchStatus;
import com.scpl.model.Notification;
import com.scpl.model.UploadBatch;
import com.scpl.model.User;
import com.scpl.model.Vote;
import com.scpl.repo.PlayerRepository;
import com.scpl.service.AdminApprovalService;
import com.scpl.service.ImportService;
import com.scpl.service.NotificationService;
import com.scpl.service.PlayerService;
import com.scpl.util.ReviewCsv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Acceptance 10, 11, 12: upload, review file, approval, partial import with a failed-rows file, size and type limits. */
class ImportFlowIntegrationTest extends TenantFixtures {

    @Autowired
    ImportService imports;
    @Autowired
    AdminApprovalService approvalService;
    @Autowired
    PlayerService playerService;
    @Autowired
    PlayerRepository players;
    @Autowired
    NotificationService notifications;

    @TempDir
    Path tmp;

    private static MockMultipartFile csv(String name, String body) {
        return new MockMultipartFile("file", name, "text/csv", body.getBytes(UTF_8));
    }

    private static final String PLAYERS_CSV = "Full Name,Email Address,Mobile No.,Playing Role\n"
            + "Aarav Test,aarav@imp.example,98200 11111,Batsman\n"
            + "Myra Test,myra@imp.example,98200 22222,Bowler\n"
            + "Duplicate Dan,dup@imp.example,98200 33333,Batsman\n";

    @Test
    void uploadReviewApproveAndFailedRowsFile() throws Exception {
        Fixture fx = newSociety(3);
        Long sid = fx.id();
        User a1 = fx.admins().get(0);
        User a2 = fx.admins().get(1);
        playerService.create(a1, sid, new PlayerRequest("Existing Dan", "dup@imp.example",
                null, null, null, null, null, null, null, null, null, null, null));

        UploadBatch uploaded = imports.submit(a1, sid, csv("players.csv", PLAYERS_CSV));
        Long id = uploaded.getId();
        assertThat(uploaded.getStatus()).isEqualTo(BatchStatus.UPLOADED);

        assertThat(imports.pollOnce()).isGreaterThanOrEqualTo(1);
        BatchView view = imports.get(a1, sid, id);
        assertThat(view.status()).isEqualTo(BatchStatus.READY_FOR_REVIEW);
        assertThat(view.hasReview()).isTrue();
        assertThat(view.report().rowsWithConflicts()).isEqualTo(1);
        assertThat(view.report().conflictCounts()).containsEntry("email_exists", 1);
        assertThat(notifications.recent(a2.getId())).extracting(Notification::getTitle).contains("Import ready for review");

        ReviewPreview preview = imports.preview(a1, sid, id);
        assertThat(preview.total()).isEqualTo(3);
        assertThat(preview.included()).isEqualTo(2);          // the conflict row defaults to Include = N
        assertThat(preview.withConflicts()).isEqualTo(1);

        // The admin edits the review file offline and decides to try the conflicting row anyway.
        List<ReviewRow> rows = ReviewCsv.read(imports.download(a1, sid, id, false).path(), 1000);
        List<ReviewRow> edited = rows.stream().map(r -> r.conflict() == null ? r
                : new ReviewRow(r.kind(), r.sourceFile(), r.sourceRow(), true, r.fields(), r.confidence(), r.conflict())).toList();
        Path editedFile = tmp.resolve("edited.csv");
        ReviewCsv.write(editedFile, edited);
        byte[] editedBytes = Files.readAllBytes(editedFile);
        imports.replaceReview(a1, sid, id, new MockMultipartFile("file", "review.csv", "text/csv", editedBytes));
        assertThat(imports.preview(a1, sid, id).included()).isEqualTo(3);

        AdminApproval approval = imports.requestApproval(a1, sid, id);
        // while the vote is open the file the admins are voting on cannot change
        assertThatThrownBy(() -> imports.replaceReview(a1, sid, id, new MockMultipartFile("file", "review.csv", "text/csv", editedBytes)))
                .isInstanceOf(BusinessRuleException.class);

        // a bulk import needs a single approval from another admin
        assertThat(approval.getApprovalsRequired()).isEqualTo(1);
        assertThat(approvalService.castVote(a2, approval.getId(), Vote.APPROVE, null).getStatus()).isEqualTo(ApprovalStatus.APPROVED);

        BatchView done = imports.get(a1, sid, id);
        assertThat(done.status()).isEqualTo(BatchStatus.PARTIALLY_APPROVED);
        assertThat(done.hasFailed()).isTrue();
        assertThat(players.countBySociety_IdAndActiveTrue(sid)).isEqualTo(3);   // Existing Dan + the two clean rows

        List<ReviewRow> failed = ReviewCsv.read(imports.download(a1, sid, id, true).path(), 100);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).conflict()).isEqualTo("email_exists");
        assertThat(failed.get(0).fields()).containsEntry("name", "Duplicate Dan");
    }

    @Test
    void aRejectedBatchStaysOutOfTheDatabase() {
        Fixture fx = newSociety(3);
        Long sid = fx.id();
        User a1 = fx.admins().get(0);
        Long id = imports.submit(a1, sid, csv("players.csv", PLAYERS_CSV)).getId();
        imports.pollOnce();
        imports.reject(a1, sid, id, "wrong file");
        assertThat(imports.get(a1, sid, id).status()).isEqualTo(BatchStatus.REJECTED);
        assertThat(players.countBySociety_IdAndActiveTrue(sid)).isZero();
    }

    @Test
    void badUploadsAreRefusedBeforeAnyParsing() {
        Fixture fx = newSociety(3);
        Long sid = fx.id();
        User a1 = fx.admins().get(0);

        assertThatThrownBy(() -> imports.submit(a1, sid, new MockMultipartFile("file", "big.csv", "text/csv", new byte[10 * 1024 * 1024 + 1])))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> imports.submit(a1, sid, new MockMultipartFile("file", "evil.exe", "application/octet-stream", new byte[]{1, 2, 3})))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("can be imported");
        assertThatThrownBy(() -> imports.submit(a1, sid, new MockMultipartFile("file", "photo.csv", "image/png", "a,b".getBytes(UTF_8))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("does not match");
        assertThatThrownBy(() -> imports.submit(a1, sid, new MockMultipartFile("file", "x.csv", "text/csv", new byte[]{'a', 0, 'b'})))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("valid");
    }

    @Test
    void anotherSocietysAdminCannotUploadIntoThisOne() {
        Fixture mine = newSociety(3);
        Fixture other = newSociety(3);
        assertThatThrownBy(() -> imports.submit(other.admins().get(0), mine.id(), csv("players.csv", PLAYERS_CSV)))
                .isInstanceOf(TenantAccessException.class);
    }
}