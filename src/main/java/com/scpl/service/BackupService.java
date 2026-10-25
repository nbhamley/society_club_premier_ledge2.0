package com.scpl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.scpl.dto.BackupDocument;
import com.scpl.dto.BackupDocument.*;
import com.scpl.dto.BackupView;
import com.scpl.dto.ImportRows.FileDownload;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AuctionLot;
import com.scpl.model.BackupRecord;
import com.scpl.model.BackupStatus;
import com.scpl.model.Society;
import com.scpl.repo.AdminContactRepository;
import com.scpl.repo.AuctionBidRepository;
import com.scpl.repo.AuctionLotRepository;
import com.scpl.repo.AwardRepository;
import com.scpl.repo.BackupRecordRepository;
import com.scpl.repo.GalleryImageRepository;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.RegistrationRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.SponsorRepository;
import com.scpl.repo.SubscriptionRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.repo.UserRepository;
import com.scpl.repo.VendorBillRepository;
import com.scpl.repo.VendorContactRepository;
import com.scpl.model.User;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Encrypted society export (§10): JSON, then gzip, then AES-256-GCM, written as
 * scpl_backup_{societyId}_{yyyyMMddHHmmss}.scplenc.
 *
 * <p>Exporting is a data-ownership right: it reads the repositories directly, so it is not blocked by
 * plan gating (a Basic society still exports its vendor data), and the billing/backup routes must stay
 * open to PAST_DUE and CANCELLED societies (ReadOnlyGuard, Phase 5). Every query is society-scoped,
 * and logins, password hashes, approvals, audit logs and payment-gateway ids are never exported.
 * Files are stored as {society-dir}/{societyId}/{recordId}.scplenc, so a client never influences a path.
 */
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final BackupRecordRepository records;
    private final SocietyRepository societies;
    private final UserRepository users;
    private final SeasonRepository seasons;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final MatchRepository matches;
    private final RegistrationRepository registrations;
    private final AuctionLotRepository lots;
    private final AuctionBidRepository bids;
    private final SponsorRepository sponsors;
    private final AwardRepository awards;
    private final GalleryImageRepository gallery;
    private final VendorContactRepository vendors;
    private final VendorBillRepository bills;
    private final AdminContactRepository contacts;
    private final SubscriptionRepository subscriptions;
    private final BackupCrypto crypto;
    private final AuditService audit;
    private final ObjectMapper baseMapper;
    private final PlatformTransactionManager txManager;

    @Value("${scpl.backup.society-dir:./data/society-backups}")
    private String societyDir;
    @Value("${scpl.backup.retention-days:30}")
    private int retentionDays;
    @Value("${scpl.backup.max-per-day:3}")
    private int maxPerDay;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private TransactionTemplate readTx() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setReadOnly(true);
        return t;
    }

    // ------------------------------------------------------------------ generate

    /**
     * Serialising, compressing and encrypting happen outside any DB transaction, so the single SQLite
     * writer is never held during the slow part.
     */
    public BackupView generate(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        LocalDateTime now = LocalDateTime.now();
        Long recordId = tx().execute(s -> {
            if (records.countBySociety_IdAndCreatedAtAfter(societyId, now.minusHours(24)) >= maxPerDay) {
                throw new BusinessRuleException("You can create " + maxPerDay + " backups per day. "
                        + "Download one of the existing backups, or try again tomorrow.");
            }
            BackupRecord r = new BackupRecord();
            r.setSociety(societies.getReferenceById(societyId));
            r.setRequestedBy(users.getReferenceById(actor.getId()));
            r.setFileName("scpl_backup_" + societyId + "_" + now.format(STAMP) + ".scplenc");
            r.setEncrypted(true);
            r.setAlgorithm(BackupCrypto.ALGORITHM + " + gzip");
            r.setStatus(BackupStatus.GENERATING);
            r.setExpiresAt(now.plusDays(retentionDays));
            return records.save(r).getId();
        });

        Path target = fileOf(societyId, recordId);
        Path tmp = target.resolveSibling(recordId + ".tmp");
        try {
            BackupDocument doc = readTx().execute(s -> buildDocument(societyId));
            byte[] json = mapper().writeValueAsBytes(doc);
            byte[] sealed = crypto.seal(societyId, LocalDate.now(ZoneOffset.UTC).toEpochDay(), BackupCrypto.gzip(json));
            Files.createDirectories(target.getParent());
            Files.write(tmp, sealed);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            String sha = BackupCrypto.sha256Hex(sealed);
            return tx().execute(s -> {
                BackupRecord r = records.findById(recordId).orElseThrow();
                r.setStatus(BackupStatus.READY);
                r.setFileSizeBytes((long) sealed.length);
                r.setSha256(sha);
                records.save(r);
                audit.record(actor, societyId, AuditActions.BACKUP_GENERATED, "BACKUP", recordId, null,
                        Map.of("file", r.getFileName(), "bytes", sealed.length));
                return toView(r);
            });
        } catch (IOException | RuntimeException e) {
            log.warn("Backup {} for society {} failed: {}", recordId, societyId, e.getClass().getSimpleName());
            FileUtils.deleteQuietly(tmp.toFile());
            FileUtils.deleteQuietly(target.toFile());
            try {
                tx().executeWithoutResult(s -> records.findById(recordId).ifPresent(r -> {
                    r.setStatus(BackupStatus.FAILED);
                    records.save(r);
                }));
            } catch (RuntimeException inner) {
                log.error("Could not mark backup {} as failed", recordId);
            }
            throw new BusinessRuleException("The backup could not be created. Please try again.");
        }
    }

    // ------------------------------------------------------------------ list / download

    public List<BackupView> list(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        return readTx().execute(s -> records.findBySociety_IdOrderByCreatedAtDesc(societyId).stream()
                .map(this::toView).toList());
    }

    /** Only the society's own admins can download; the platform admin can restore a file but never fetch one. */
    public FileDownload download(User actor, Long societyId, Long backupId) {
        Tenancy.requireAdminOf(actor, societyId);
        return tx().execute(s -> {
            BackupRecord r = records.findByIdAndSociety_Id(backupId, societyId)
                    .orElseThrow(() -> new NotFoundException("Backup not found"));
            if (!r.isDownloadable()) {
                throw new BusinessRuleException("This backup is not available");
            }
            Path p = fileOf(societyId, backupId);
            if (!Files.isRegularFile(p)) {
                throw new NotFoundException("This backup file has expired");
            }
            try {
                if (r.getSha256() != null && !r.getSha256().equals(BackupCrypto.sha256Hex(Files.readAllBytes(p)))) {
                    throw new BusinessRuleException("The stored backup failed its integrity check. Create a new one.");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            r.setStatus(BackupStatus.DOWNLOADED);
            records.save(r);
            audit.record(actor, societyId, AuditActions.BACKUP_DOWNLOADED, "BACKUP", backupId, null,
                    Map.of("file", r.getFileName()));
            return new FileDownload(r.getFileName(), p);
        });
    }

    // ------------------------------------------------------------------ retention (BackupPurgeJob)

    /**
     * Deletes the file and the record row together. BackupStatus has no EXPIRED value, so the row is
     * removed rather than marked. Returns how many records were purged.
     */
    public int purgeExpired(LocalDateTime now) {
        return tx().execute(s -> {
            List<BackupRecord> due = records.findByExpiresAtBeforeAndStatusIn(now, EnumSet.allOf(BackupStatus.class));
            for (BackupRecord r : due) {
                FileUtils.deleteQuietly(fileOf(r.getSocietyId(), r.getId()).toFile());
                records.delete(r);
            }
            return due.size();
        });
    }

    // ------------------------------------------------------------------ document

    BackupDocument buildDocument(Long societyId) {
        Society s = societies.findById(societyId).orElseThrow(() -> new NotFoundException("Society not found"));
        List<AuctionLot> lotList = lots.findBySociety_Id(societyId);
        return new BackupDocument(BackupDocument.CURRENT_VERSION, LocalDateTime.now(ZoneOffset.UTC),
                new SocietyData(s.getName(), s.getSlug(), s.getDescription(), s.getCity(), s.getAddress(),
                        s.getLogoUrl(), s.getContactEmail(), s.getContactPhone(), s.getCreatedAt()),
                seasons.findBySociety_IdOrderByCreatedAtDesc(societyId).stream().map(x -> new SeasonData(x.getId(),
                        x.getName(), x.getYear(), x.getStatus(), x.getStartDate(), x.getEndDate(),
                        Boolean.TRUE.equals(x.getRegistrationOpen()), x.getOversPerMatch(), x.getTeamPurse(),
                        x.getPlannedBudget(), x.getDescription(), x.getCreatedAt())).toList(),
                teams.findBySociety_IdOrderByNameAsc(societyId).stream().map(x -> new TeamData(x.getId(),
                        x.getSeasonId(), x.getName(), x.getShortName(), x.getColor(), x.getLogoUrl(),
                        x.getCaptain() == null ? null : x.getCaptain().getId(), x.getPurseRemaining(),
                        x.getCreatedAt())).toList(),
                players.findBySociety_IdOrderByNameAsc(societyId).stream().map(x -> new PlayerData(x.getId(),
                        x.getTeamId(), x.getName(), x.getEmail(), x.getPhone(), x.getDateOfBirth(), x.getAddress(),
                        x.getFlatNumber(), x.getRole(), x.getBattingStyle(), x.getBowlingStyle(), x.getJerseyNumber(),
                        x.getBasePrice(), x.getPhotoUrl(), x.getMatchesPlayed(), x.getRuns(), x.getWickets(),
                        Boolean.TRUE.equals(x.getActive()), x.getCreatedAt())).toList(),
                matches.findBySociety_IdOrderByMatchDateAsc(societyId).stream().map(x -> new MatchData(x.getId(),
                        x.getSeason().getId(), x.getTeamA().getId(), x.getTeamB().getId(), x.getMatchDate(),
                        x.getVenue(), x.getStage(), x.getStatus(), x.getTeamARuns(), x.getTeamAWickets(),
                        x.getTeamAOvers(), x.getTeamBRuns(), x.getTeamBWickets(), x.getTeamBOvers(),
                        x.getWinnerTeam() == null ? null : x.getWinnerTeam().getId(), x.getResultSummary(),
                        x.getCreatedAt())).toList(),
                registrations.findBySociety_IdOrderByCreatedAtDesc(societyId).stream().map(x -> new RegistrationData(
                        x.getId(), x.getSeason().getId(), x.getFullName(), x.getEmail(), x.getPhone(),
                        x.getDateOfBirth(), x.getFlatNumber(), x.getRole(), x.getBattingStyle(), x.getBowlingStyle(),
                        x.getNotes(), x.getStatus(), x.getPlayer() == null ? null : x.getPlayer().getId(),
                        x.getCreatedAt())).toList(),
                lotList.stream().map(x -> new LotData(x.getId(), x.getSeason().getId(), x.getPlayer().getId(),
                        x.getBasePrice(), x.getCurrentBid(),
                        x.getCurrentBidTeam() == null ? null : x.getCurrentBidTeam().getId(), x.getSoldPrice(),
                        x.getSoldTeam() == null ? null : x.getSoldTeam().getId(), x.getStatus(), x.getMode(),
                        x.getCreatedAt())).toList(),
                lotList.stream().flatMap(l -> bids.findByLot_IdOrderByAmountDesc(l.getId()).stream()
                        .map(b -> new BidData(l.getId(), b.getTeam().getId(), b.getAmount(), b.getBidAt()))).toList(),
                sponsors.findBySociety_IdOrderByNameAsc(societyId).stream().map(x -> new SponsorData(
                        x.getSeason() == null ? null : x.getSeason().getId(), x.getName(), x.getTier(), x.getLogoUrl(),
                        x.getWebsite(), x.getAmount(), Boolean.TRUE.equals(x.getActive()), x.getCreatedAt())).toList(),
                awards.findBySociety_IdOrderByCreatedAtDesc(societyId).stream().map(x -> new AwardData(
                        x.getSeason() == null ? null : x.getSeason().getId(),
                        x.getPlayer() == null ? null : x.getPlayer().getId(),
                        x.getTeam() == null ? null : x.getTeam().getId(), x.getTitle(), x.getCategory(),
                        x.getDescription(), x.getImageUrl(), x.getCreatedAt())).toList(),
                gallery.findBySociety_IdOrderByCreatedAtDesc(societyId).stream().map(x -> new GalleryData(
                        x.getSeason() == null ? null : x.getSeason().getId(), x.getTitle(), x.getImageUrl(),
                        x.getCaption(), x.getCreatedAt())).toList(),
                vendors.findBySociety_IdOrderByNameAsc(societyId).stream().map(x -> new VendorData(x.getId(),
                        x.getName(), x.getCategory(), x.getContactPerson(), x.getPhone(), x.getWhatsappNumber(),
                        x.getEmail(), x.getAddress(), x.getCity(), x.getWebsite(), x.getRating(), x.getNotes(),
                        Boolean.TRUE.equals(x.getActive()), x.getCreatedAt())).toList(),
                bills.findBySociety_IdOrderByBillDateDesc(societyId).stream().map(x -> new BillData(
                        x.getVendor().getId(), x.getSeason() == null ? null : x.getSeason().getId(), x.getTitle(),
                        x.getAmount(), x.getBillDate(), x.getPaidDate(), x.getStatus(), x.getDescription(),
                        x.getAttachmentUrl(), x.getCreatedAt())).toList(),
                contacts.findBySociety_IdOrderByNameAsc(societyId).stream().map(x -> new ContactData(x.getName(),
                        x.getRoleTitle(), x.getPhone(), x.getWhatsappNumber(), x.getEmail(), x.getCreatedAt())).toList(),
                subscriptions.findBySociety_IdOrderByCreatedAtDesc(societyId).stream().map(x -> new SubscriptionData(
                        x.getPlan(), x.getStatus(), x.getStartedAt(), x.getExpiresAt(), x.getMonthlyCost(),
                        x.getCurrency(), Boolean.TRUE.equals(x.getAutoRenew()), x.getLastPaymentAt(),
                        x.getNextBillingAt(), x.getNextPlan())).toList());
    }

    // ------------------------------------------------------------------ internals

    /** Readable ISO dates in the JSON rather than numeric arrays. */
    private ObjectMapper mapper() {
        return baseMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private Path fileOf(Long societyId, Long recordId) {
        Path base = Paths.get(societyDir).toAbsolutePath().normalize();
        Path p = base.resolve(String.valueOf(societyId)).resolve(recordId + ".scplenc").normalize();
        if (!p.startsWith(base)) {
            throw new IllegalStateException("Backup path escaped the backup directory");
        }
        return p;
    }

    private BackupView toView(BackupRecord r) {
        return new BackupView(r.getId(), r.getFileName(),
                r.getFileSizeBytes() == null ? "-" : FileUtils.byteCountToDisplaySize(r.getFileSizeBytes()),
                r.getStatus(), r.getStatus().getLabel(), Boolean.TRUE.equals(r.getEncrypted()), r.getAlgorithm(),
                r.getSha256(), r.getCreatedAt(), r.getExpiresAt(), r.isDownloadable());
    }
}