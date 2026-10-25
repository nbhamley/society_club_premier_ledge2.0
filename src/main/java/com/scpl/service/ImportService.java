package com.scpl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scpl.dto.ImportKind;
import com.scpl.dto.ImportRows.BatchView;
import com.scpl.dto.ImportRows.FileDownload;
import com.scpl.dto.ImportRows.ReviewPreview;
import com.scpl.dto.ImportRows.ReviewRow;
import com.scpl.dto.ImportRows.SegregatedRow;
import com.scpl.dto.ImportRows.SegregationInput;
import com.scpl.dto.ImportRows.SegregationReport;
import com.scpl.dto.ImportRows.SegregationResult;
import com.scpl.dto.ImportRows.Table;
import com.scpl.dto.ImportRows.TableSummary;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AdminApproval;
import com.scpl.model.ApprovalEntityType;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.BatchStatus;
import com.scpl.model.UploadBatch;
import com.scpl.model.User;
import com.scpl.repo.AwardRepository;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.repo.UploadBatchRepository;
import com.scpl.repo.UserRepository;
import com.scpl.repo.VendorBillRepository;
import com.scpl.repo.VendorContactRepository;
import com.scpl.util.ReviewCsv;
import com.scpl.util.TabularReader;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bulk import (§9): upload, segregate, review, approve (3-of-N), apply.
 *
 * <p>Transactions: SQLite allows one writer, so parsing and the AI call run outside any transaction and
 * only short ones touch the database. A batch moves UPLOADED to SEGREGATING by an atomic claim, so
 * overlapping poller runs cannot process one file twice.
 *
 * <p>Applying a batch runs inside the transaction of the final approving vote (via ApprovalDecidedEvent).
 * Known row problems go to {batchId}.failed.csv. An unexpected error rolls back the whole import and the
 * approval stays PENDING, so a batch is never half-applied.
 *
 * <p>Files live under scpl.uploads.dir/{societyId}/{batchId}/ and are named by this class (original.ext,
 * review.csv, {batchId}.failed.csv). A client-supplied filename is never used as a path.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final long PER_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final long TOTAL_UNPACKED_BYTES = 40L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 20;
    private static final int MAX_PER_POLL = 5;
    private static final int PREVIEW_LIMIT = 200;
    private static final int STUCK_MINUTES = 30;
    private static final Set<BatchStatus> OPEN =
            EnumSet.of(BatchStatus.UPLOADED, BatchStatus.SEGREGATING, BatchStatus.READY_FOR_REVIEW);

    private static final Map<String, Set<String>> MIME_BY_EXT = Map.of(
            "csv", Set.of("text/csv", "text/plain", "application/csv", "application/vnd.ms-excel",
                    "text/comma-separated-values"),
            "json", Set.of("application/json", "text/json", "text/plain"),
            "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/octet-stream", "application/zip"),
            "zip", Set.of("application/zip", "application/x-zip-compressed", "application/octet-stream",
                    "multipart/x-zip"));

    private record BatchInfo(Long societyId, String fileName) {
    }

    private final UploadBatchRepository batches;
    private final SocietyRepository societies;
    private final UserRepository users;
    private final SeasonRepository seasons;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final VendorContactRepository vendors;
    private final VendorBillRepository bills;
    private final MatchRepository matches;
    private final AwardRepository awards;
    private final DataSegregationProvider provider;
    private final AdminApprovalService approvals;
    private final QuotaService quota;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    @Value("${scpl.uploads.dir:./data/uploads}")
    private String uploadsDir;
    @Value("${scpl.uploads.max-bytes:10485760}")
    private long maxBytes;
    @Value("${scpl.uploads.allowed-extensions:csv,json,xlsx,zip}")
    private String allowedExtensionsCsv;
    @Value("${scpl.uploads.max-rows:5000}")
    private int maxRows;
    @Value("${scpl.uploads.review-days:14}")
    private int reviewDays;
    @Value("${scpl.uploads.retention-days-rejected:30}")
    private int retentionDays;
    @Value("${scpl.uploads.max-open-batches:10}")
    private int maxOpenBatches;
    @Value("${scpl.auction.default-base-price:1000}")
    private double defaultBasePrice;

    public ImportService(UploadBatchRepository batches, SocietyRepository societies, UserRepository users,
                         SeasonRepository seasons, TeamRepository teams, PlayerRepository players,
                         VendorContactRepository vendors, VendorBillRepository bills, MatchRepository matches,
                         AwardRepository awards, DataSegregationProvider provider, AdminApprovalService approvals,
                         QuotaService quota, NotificationService notifications, AuditService audit,
                         ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.batches = batches;
        this.societies = societies;
        this.users = users;
        this.seasons = seasons;
        this.teams = teams;
        this.players = players;
        this.vendors = vendors;
        this.bills = bills;
        this.matches = matches;
        this.awards = awards;
        this.provider = provider;
        this.approvals = approvals;
        this.quota = quota;
        this.notifications = notifications;
        this.audit = audit;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    // ------------------------------------------------------------------ upload

    /** Validates and stores the file, and records the batch as UPLOADED. The poller does the rest. */
    @Transactional
    public UploadBatch submit(User actor, Long societyId, MultipartFile file) {
        Tenancy.requireAdminOf(actor, societyId);
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Choose a file to upload");
        }
        // Size is checked first and before any parsing (acceptance test 12).
        if (file.getSize() > maxBytes) {
            throw new BusinessRuleException("The file is " + FileUtils.byteCountToDisplaySize(file.getSize())
                    + ". The limit is " + FileUtils.byteCountToDisplaySize(maxBytes) + ".");
        }
        String display = displayName(file.getOriginalFilename());
        String ext = FilenameUtils.getExtension(display).toLowerCase(Locale.ROOT);
        if (!allowedExtensions().contains(ext) || !MIME_BY_EXT.containsKey(ext)) {
            throw new BusinessRuleException("Only " + String.join(", ", allowedExtensions()) + " files can be imported");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!mime.isEmpty() && !MIME_BY_EXT.get(ext).contains(mime)) {
            throw new BusinessRuleException("The file type '" + mime + "' does not match a ." + ext + " file");
        }
        sniff(file, ext);
        long open = OPEN.stream().mapToLong(st -> batches.countBySociety_IdAndStatus(societyId, st)).sum();
        if (open >= maxOpenBatches) {
            throw new BusinessRuleException("You already have " + open
                    + " imports waiting. Approve or reject some before uploading more.");
        }

        UploadBatch b = new UploadBatch();
        b.setSociety(societies.getReferenceById(societyId));
        b.setUploadedBy(users.getReferenceById(actor.getId()));
        b.setOriginalFileName(display);
        b.setFileSizeBytes(file.getSize());
        b.setMimeType(mime.isEmpty() ? null : mime);
        b.setStatus(BatchStatus.UPLOADED);
        b = batches.save(b);

        Path dir = dir(societyId, b.getId());
        Path stored = dir.resolve("original." + ext);
        try {
            Files.createDirectories(dir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, stored, StandardCopyOption.REPLACE_EXISTING);
            }
            // A second, server-side size check on what actually reached the disk.
            if (FileUtils.sizeOf(stored.toFile()) > maxBytes) {
                FileUtils.deleteQuietly(dir.toFile());
                throw new BusinessRuleException("The file is larger than " + FileUtils.byteCountToDisplaySize(maxBytes));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the upload", e);
        }
        audit.record(actor, societyId, AuditActions.IMPORT_UPLOADED, "UPLOAD_BATCH", b.getId(), null,
                Map.of("file", display, "bytes", file.getSize()));
        return b;
    }

    // ------------------------------------------------------------------ polling (ImportPollingJob)

    /** One poller pass. Returns the number of batches it moved out of UPLOADED. */
    public int pollOnce() {
        LocalDateTime now = LocalDateTime.now();
        // Recover batches left in SEGREGATING by a crash: processing never legitimately takes this long.
        List<Long> stuck = readTx.execute(s -> batches.findByStatusOrderByCreatedAtAsc(BatchStatus.SEGREGATING).stream()
                .filter(b -> b.getCreatedAt().isBefore(now.minusMinutes(STUCK_MINUTES))).map(UploadBatch::getId).toList());
        if (stuck != null) {
            stuck.forEach(id -> tx.execute(s -> batches.transition(id, BatchStatus.SEGREGATING, BatchStatus.UPLOADED)));
        }
        List<Long> ids = readTx.execute(s -> batches.findByStatusOrderByCreatedAtAsc(BatchStatus.UPLOADED).stream()
                .limit(MAX_PER_POLL).map(UploadBatch::getId).toList());
        int done = 0;
        for (Long id : ids == null ? List.<Long>of() : ids) {
            if (process(id)) {
                done++;
            }
        }
        return done;
    }

    boolean process(Long batchId) {
        Integer claimed = tx.execute(s -> batches.transition(batchId, BatchStatus.UPLOADED, BatchStatus.SEGREGATING));
        if (claimed == null || claimed == 0) {
            return false;
        }
        try {
            BatchInfo info = readTx.execute(s -> {
                UploadBatch b = batches.findById(batchId).orElseThrow(() -> new NotFoundException("Batch not found"));
                return new BatchInfo(b.getSocietyId(), b.getOriginalFileName());
            });
            Long societyId = info.societyId();
            String ext = FilenameUtils.getExtension(info.fileName()).toLowerCase(Locale.ROOT);
            Path original = dir(societyId, batchId).resolve("original." + ext);

            List<Table> tables = TabularReader.read(original, info.fileName(),
                    new TabularReader.Limits(MAX_ZIP_ENTRIES, PER_ENTRY_BYTES, TOTAL_UNPACKED_BYTES, maxRows));
            boolean aiAllowed = quota.limitsFor(societyId).aiImport();
            SegregationResult result = provider.segregate(
                    new SegregationInput(societyId, info.fileName(), original, tables, aiAllowed));
            if (result.rows().isEmpty()) {
                String why = result.warnings().isEmpty() ? "" : " " + result.warnings().get(0);
                throw new BusinessRuleException("No recognisable data was found. Check the column headings." + why);
            }
            List<ReviewRow> proposed = new ArrayList<>();
            for (SegregatedRow r : result.rows()) {
                proposed.add(new ReviewRow(r.kind(), r.sourceFile(), r.sourceRow(), true, r.fields(), r.confidence(), null));
            }
            List<ReviewRow> reviewed = dryRun(societyId, proposed, false);
            ReviewCsv.write(dir(societyId, batchId).resolve("review.csv"), reviewed);
            SegregationReport report = report(result.provider(), result.tables(), result.warnings(), reviewed);
            tx.executeWithoutResult(s -> markReady(batchId, report));
            return true;
        } catch (Exception e) {
            // Only the type is logged: messages from parsers can quote cell contents (PII).
            log.warn("Import batch {} failed: {}", batchId, e.getClass().getSimpleName());
            String message = e instanceof BusinessRuleException ? e.getMessage() : "The file could not be processed.";
            try {
                tx.executeWithoutResult(s -> markFailed(batchId, message));
            } catch (RuntimeException inner) {
                log.error("Could not record failure of import batch {}", batchId);
            }
            return false;
        }
    }

    private void markReady(Long batchId, SegregationReport report) {
        UploadBatch b = batches.findById(batchId).orElseThrow();
        b.setSegregationReport(toJson(report));
        b.setReviewFileUrl("/society-admin/import/" + batchId + "/review");
        b.setStatus(BatchStatus.READY_FOR_REVIEW);
        b.setReviewDeadline(LocalDateTime.now().plusDays(reviewDays));
        batches.save(b);
        notifications.notifyAdmins(b.getSocietyId(), null, "Import ready for review",
                b.getOriginalFileName() + ": " + report.totalRows() + " rows found, "
                        + report.rowsWithConflicts() + " with conflicts.", "/society-admin#import");
    }

    private void markFailed(Long batchId, String message) {
        UploadBatch b = batches.findById(batchId).orElseThrow();
        b.setStatus(BatchStatus.FAILED);
        b.setErrorMessage(message);
        batches.save(b);
        notifications.notifyUser(b.getUploadedBy(), b.getSocietyId(), "Import failed",
                b.getOriginalFileName() + ": " + message, "/society-admin#import");
        audit.record(null, b.getSocietyId(), AuditActions.IMPORT_FAILED, "UPLOAD_BATCH", batchId, null,
                Map.of("reason", message));
    }

    // ------------------------------------------------------------------ review

    @Transactional(readOnly = true)
    public List<BatchView> list(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        return batches.findBySociety_IdOrderByCreatedAtDesc(societyId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public BatchView get(User actor, Long societyId, Long batchId) {
        Tenancy.requireAdminOf(actor, societyId);
        return toView(load(societyId, batchId));
    }

    @Transactional(readOnly = true)
    public ReviewPreview preview(User actor, Long societyId, Long batchId) {
        Tenancy.requireAdminOf(actor, societyId);
        UploadBatch b = load(societyId, batchId);
        Path p = dir(societyId, b.getId()).resolve("review.csv");
        if (!Files.isRegularFile(p)) {
            throw new NotFoundException("There is no review file for this batch");
        }
        try {
            List<ReviewRow> rows = ReviewCsv.read(p, maxRows);
            int included = (int) rows.stream().filter(ReviewRow::include).count();
            int conflicts = (int) rows.stream().filter(r -> r.conflict() != null).count();
            return new ReviewPreview(rows.size(), included, conflicts, rows.stream().limit(PREVIEW_LIMIT).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Streams review.csv, or the failed-rows file when failed is true. */
    @Transactional(readOnly = true)
    public FileDownload download(User actor, Long societyId, Long batchId, boolean failed) {
        Tenancy.requireAdminOf(actor, societyId);
        UploadBatch b = load(societyId, batchId);
        String name = failed ? batchId + ".failed.csv" : "review.csv";
        Path p = dir(societyId, b.getId()).resolve(name);
        if (!Files.isRegularFile(p) || (failed ? b.getFailedFileUrl() : b.getReviewFileUrl()) == null) {
            throw new NotFoundException("That file is not available");
        }
        return new FileDownload(failed ? name : "review-" + batchId + ".csv", p);
    }

    /**
     * The admin edited review.csv offline and uploads it back. Conflicts are recomputed from scratch, so
     * an edited Conflict column is never trusted. Blocked while an approval is pending.
     */
    public UploadBatch replaceReview(User actor, Long societyId, Long batchId, MultipartFile file) {
        Tenancy.requireAdminOf(actor, societyId);
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Choose the edited review file");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessRuleException("The file is larger than " + FileUtils.byteCountToDisplaySize(maxBytes));
        }
        if (!"csv".equals(FilenameUtils.getExtension(displayName(file.getOriginalFilename())).toLowerCase(Locale.ROOT))) {
            throw new BusinessRuleException("Upload the edited review.csv file");
        }
        SegregationReport previous = readTx.execute(s -> {
            UploadBatch b = load(societyId, batchId);
            requireReviewable(b);
            return fromJson(b.getSegregationReport());
        });
        List<ReviewRow> edited;
        try {
            edited = ReviewCsv.fromTable(TabularReader.parseCsv(file.getBytes(), "review.csv"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (edited.isEmpty() || edited.size() > maxRows) {
            throw new BusinessRuleException("The review file must have between 1 and " + maxRows + " rows");
        }
        List<ReviewRow> reviewed = dryRun(societyId, edited, true);
        try {
            ReviewCsv.write(dir(societyId, batchId).resolve("review.csv"), reviewed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SegregationReport report = report(previous == null ? "manual-edit" : previous.provider(),
                previous == null ? List.of() : previous.tables(), previous == null ? List.of() : previous.warnings(), reviewed);
        return tx.execute(s -> {
            UploadBatch b = load(societyId, batchId);
            b.setSegregationReport(toJson(report));
            batches.save(b);
            audit.record(actor, societyId, AuditActions.IMPORT_REVIEW_REPLACED, "UPLOAD_BATCH", batchId, null,
                    Map.of("rows", reviewed.size()));
            return b;
        });
    }

    // ------------------------------------------------------------------ approve / reject

    /** Raises the BULK_IMPORT approval. The review file's hash is pinned so the vote covers exactly these rows. */
    @Transactional
    public AdminApproval requestApproval(User actor, Long societyId, Long batchId) {
        Tenancy.requireAdminOf(actor, societyId);
        UploadBatch b = load(societyId, batchId);
        requireReviewable(b);
        Path review = dir(societyId, batchId).resolve("review.csv");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(review);
        } catch (IOException e) {
            throw new NotFoundException("There is no review file for this batch");
        }
        long included = ReviewCsv.fromTable(TabularReader.parseCsv(bytes, "review.csv")).stream()
                .filter(ReviewRow::include).count();
        if (included == 0) {
            throw new BusinessRuleException("No rows are set to be imported. Set Include to Y on at least one row.");
        }
        String payload = toJson(Map.of("reviewSha256", BackupCrypto.sha256Hex(bytes)));
        AdminApproval a = approvals.raise(actor, societyId, ApprovalEntityType.BULK_IMPORT, batchId, null,
                "Import " + included + " rows from " + b.getOriginalFileName(), payload);
        b.setApproval(a);
        batches.save(b);
        return a;
    }

    @Transactional
    public UploadBatch reject(User actor, Long societyId, Long batchId, String reason) {
        Tenancy.requireAdminOf(actor, societyId);
        UploadBatch b = load(societyId, batchId);
        requireReviewable(b);
        markRejected(b, reason == null || reason.isBlank() ? "Rejected by an admin" : reason.trim());
        audit.record(actor, societyId, AuditActions.IMPORT_REJECTED, "UPLOAD_BATCH", batchId, null,
                Map.of("file", b.getOriginalFileName()));
        return b;
    }

    private void markRejected(UploadBatch b, String why) {
        b.setStatus(BatchStatus.REJECTED);
        b.setErrorMessage(why.length() > 300 ? why.substring(0, 300) : why);
        // No rejected_at column: reviewDeadline doubles as the purge date once a batch is rejected.
        b.setReviewDeadline(LocalDateTime.now().plusDays(retentionDays));
        batches.save(b);
    }

    private static void requireReviewable(UploadBatch b) {
        if (b.getStatus() != BatchStatus.READY_FOR_REVIEW) {
            throw new BusinessRuleException("This batch is " + b.getStatus().getLabel().toLowerCase(Locale.ROOT)
                    + " and can no longer be changed");
        }
        if (b.getApproval() != null && b.getApproval().isPending()) {
            throw new BusinessRuleException("An approval is already collecting votes. Withdraw it before changing the batch.");
        }
    }

    // ------------------------------------------------------------------ approval outcome

    @EventListener
    @Transactional
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        AdminApproval a = event.approval();
        if (!ApprovalEntityType.BULK_IMPORT.equals(a.getEntityType())) {
            return;
        }
        UploadBatch b = batches.findByIdAndSociety_Id(a.getEntityId(), a.getSocietyId()).orElse(null);
        if (b == null) {
            return;
        }
        switch (a.getStatus()) {
            case APPROVED -> apply(a, b, event.decidedBy());
            case REJECTED -> {
                markRejected(b, "Rejected by vote of the admin team");
                audit.record(event.decidedBy(), a.getSocietyId(), AuditActions.IMPORT_REJECTED, "UPLOAD_BATCH", b.getId(),
                        null, Map.of("file", b.getOriginalFileName()));
            }
            case WITHDRAWN -> {
                b.setApproval(null); // back to a plain review state so it can be edited or re-raised
                batches.save(b);
            }
            default -> { }
        }
    }

    /** Inserts the included rows. Runs in the deciding vote's transaction (see class comment). */
    private void apply(AdminApproval a, UploadBatch b, User decidedBy) {
        Long societyId = b.getSocietyId();
        Long batchId = b.getId();
        if (b.getStatus() != BatchStatus.READY_FOR_REVIEW || b.getApproval() == null
                || !b.getApproval().getId().equals(a.getId())) {
            throw new BusinessRuleException("This batch is no longer awaiting import");
        }
        Path dir = dir(societyId, batchId);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(dir.resolve("review.csv"));
        } catch (IOException e) {
            throw new BusinessRuleException("The review file for this batch is missing");
        }
        String pinned = payloadHash(a.getPayload());
        if (pinned == null || !pinned.equals(BackupCrypto.sha256Hex(bytes))) {
            throw new BusinessRuleException("The review file changed after the approval was raised. Withdraw it and raise it again.");
        }
        List<ReviewRow> rows = ReviewCsv.fromTable(TabularReader.parseCsv(bytes, "review.csv"));
        if (rows.size() > maxRows) {
            throw new BusinessRuleException("The review file has too many rows");
        }

        ImportRowHandler handler = new ImportRowHandler(deps(), societyId, quota.limitsFor(societyId), true);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).include()) {
                order.add(i);
            }
        }
        order.sort(Comparator.comparingInt(i -> rows.get(i).kind().ordinal()));
        List<ReviewRow> failed = new ArrayList<>();
        for (int idx : order) {
            ReviewRow r = rows.get(idx);
            String conflict = handler.process(r.kind(), r.fields());
            if (conflict != null) {
                failed.add(r.withConflict(conflict));
            }
        }
        int included = order.size();
        int inserted = included - failed.size();
        int skipped = rows.size() - included;

        if (!failed.isEmpty()) {
            try {
                ReviewCsv.write(dir.resolve(batchId + ".failed.csv"), failed);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            b.setFailedFileUrl("/society-admin/import/" + batchId + "/failed");
        }
        b.setStatus(failed.isEmpty() ? BatchStatus.APPROVED : BatchStatus.PARTIALLY_APPROVED);
        b.setApprovedAt(LocalDateTime.now());
        b.setApprovedBy(users.getReferenceById(decidedBy.getId()));
        batches.save(b);

        audit.record(decidedBy, societyId, AuditActions.IMPORT_APPROVED, "UPLOAD_BATCH", batchId, null,
                Map.of("inserted", inserted, "failed", failed.size(), "skipped", skipped));
        notifications.notifyAdmins(societyId, null, "Import finished",
                inserted + " rows imported" + (failed.isEmpty() ? "." : ", " + failed.size()
                        + " could not be imported. Download the failed-rows file, fix it and upload it as a new batch."),
                "/society-admin#import");
    }

    // ------------------------------------------------------------------ retention (called by a scheduled job)

    /** Expires unreviewed batches and deletes the files (they contain PII) of finished ones. */
    @Transactional
    public Map<String, Integer> purgeExpired(LocalDateTime now) {
        int expired = 0;
        int purged = 0;
        for (UploadBatch b : batches.findByStatusAndReviewDeadlineBefore(BatchStatus.READY_FOR_REVIEW, now)) {
            if (b.getApproval() != null && b.getApproval().isPending()) {
                continue; // admins are mid-vote; leave it alone
            }
            markRejected(b, "The review deadline passed");
            audit.record(null, b.getSocietyId(), AuditActions.IMPORT_REJECTED, "UPLOAD_BATCH", b.getId(), null,
                    Map.of("reason", "deadline"));
            notifications.notifyAdmins(b.getSocietyId(), null, "Import expired",
                    b.getOriginalFileName() + " was not approved in time.", "/society-admin#import");
            expired++;
        }
        List<UploadBatch> due = new ArrayList<>(batches.findByStatusAndReviewDeadlineBefore(BatchStatus.REJECTED, now));
        due.addAll(batches.findByStatusInAndCreatedAtBefore(
                EnumSet.of(BatchStatus.APPROVED, BatchStatus.PARTIALLY_APPROVED, BatchStatus.FAILED),
                now.minusDays(retentionDays)));
        for (UploadBatch b : due) {
            Path dir = dir(b.getSocietyId(), b.getId());
            if (Files.exists(dir)) {
                FileUtils.deleteQuietly(dir.toFile());
                b.setReviewFileUrl(null);
                b.setFailedFileUrl(null);
                batches.save(b);
                purged++;
            }
        }
        return Map.of("expired", expired, "filesDeleted", purged);
    }

    // ------------------------------------------------------------------ internals

    private ImportRowHandler.Deps deps() {
        return new ImportRowHandler.Deps(societies, seasons, teams, players, vendors, bills, matches, awards,
                defaultBasePrice);
    }

    /**
     * Review-mode pass: computes a conflict code for each row without writing anything. Rows are
     * processed in ImportKind order (teams before matches, vendors before bills) but returned in
     * their original order. With honourInclude=false every row is checked and rows with a conflict
     * default to Include = N; with true the admin's own Y/N choices are kept and N rows are not checked.
     */
    private List<ReviewRow> dryRun(Long societyId, List<ReviewRow> rows, boolean honourInclude) {
        return readTx.execute(s -> {
            ImportRowHandler handler = new ImportRowHandler(deps(), societyId, quota.limitsFor(societyId), false);
            ReviewRow[] out = new ReviewRow[rows.size()];
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingInt(i -> rows.get(i).kind().ordinal()));
            for (int idx : order) {
                ReviewRow r = rows.get(idx);
                if (honourInclude && !r.include()) {
                    out[idx] = r;
                    continue;
                }
                String conflict = handler.process(r.kind(), r.fields());
                out[idx] = new ReviewRow(r.kind(), r.sourceFile(), r.sourceRow(),
                        honourInclude ? r.include() : conflict == null, r.fields(), r.confidence(), conflict);
            }
            return List.of(out);
        });
    }

    private SegregationReport report(String providerName, List<TableSummary> tables, List<String> warnings,
                                     List<ReviewRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> conflicts = new LinkedHashMap<>();
        int withConflicts = 0;
        for (ReviewRow r : rows) {
            counts.merge(r.kind().getLabel(), 1, Integer::sum);
            if (r.conflict() != null) {
                withConflicts++;
                conflicts.merge(r.conflict(), 1, Integer::sum);
            }
        }
        return new SegregationReport(providerName, rows.size(), counts, conflicts, withConflicts, tables, warnings);
    }

    private UploadBatch load(Long societyId, Long batchId) {
        return batches.findByIdAndSociety_Id(batchId, societyId)
                .orElseThrow(() -> new NotFoundException("Import batch not found"));
    }

    private BatchView toView(UploadBatch b) {
        User u = b.getUploadedBy();
        String who = u.getFullName() != null ? u.getFullName() : u.getUsername();
        AdminApproval a = b.getApproval();
        return new BatchView(b.getId(), b.getOriginalFileName(), b.getFileSizeBytes(),
                FileUtils.byteCountToDisplaySize(b.getFileSizeBytes()), b.getStatus(), b.getStatus().getLabel(), who,
                b.getCreatedAt(), b.getReviewDeadline(), b.getErrorMessage(), b.getReviewFileUrl() != null,
                b.getFailedFileUrl() != null, a == null ? null : a.getId(),
                a == null ? null : a.getStatus().getLabel(), fromJson(b.getSegregationReport()));
    }

    private Path base() {
        return Paths.get(uploadsDir).toAbsolutePath().normalize();
    }

    private Path dir(Long societyId, Long batchId) {
        Path p = base().resolve(String.valueOf(societyId)).resolve(String.valueOf(batchId)).normalize();
        if (!p.startsWith(base())) {
            throw new IllegalStateException("Upload path escaped the upload directory");
        }
        return p;
    }

    private Set<String> allowedExtensions() {
        return Arrays.stream(allowedExtensionsCsv.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /** Display-only name: control characters removed, path stripped, length capped. Never used as a path. */
    private static String displayName(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "");
        String name = FilenameUtils.getName(cleaned);
        if (name.isBlank()) {
            throw new BusinessRuleException("The file has no name");
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    /** Cheap content check so a renamed executable or binary is refused before it is stored. */
    private static void sniff(MultipartFile file, String ext) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(512);
            if ("xlsx".equals(ext) || "zip".equals(ext)) {
                if (head.length < 4 || head[0] != 'P' || head[1] != 'K') {
                    throw new BusinessRuleException("The file does not look like a valid ." + ext + " file");
                }
            } else {
                for (byte x : head) {
                    if (x == 0) {
                        throw new BusinessRuleException("The file does not look like a valid ." + ext + " file");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String payloadHash(String payload) {
        try {
            return payload == null ? null : mapper.readTree(payload).path("reviewSha256").asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise import data", e);
        }
    }

    private SegregationReport fromJson(String json) {
        try {
            return json == null ? null : mapper.readValue(json, SegregationReport.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}