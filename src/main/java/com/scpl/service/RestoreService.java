package com.scpl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scpl.dto.BackupDocument;
import com.scpl.dto.BackupDocument.*;
import com.scpl.dto.NewAdminRequest;
import com.scpl.dto.SocietyRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.AdminContact;
import com.scpl.model.AuctionBid;
import com.scpl.model.AuctionLot;
import com.scpl.model.AuctionMode;
import com.scpl.model.Award;
import com.scpl.model.BackupRecord;
import com.scpl.model.BillStatus;
import com.scpl.model.GalleryImage;
import com.scpl.model.LotStatus;
import com.scpl.model.Match;
import com.scpl.model.MatchStatus;
import com.scpl.model.Player;
import com.scpl.model.Registration;
import com.scpl.model.RegistrationStatus;
import com.scpl.model.Season;
import com.scpl.model.SeasonStatus;
import com.scpl.model.Society;
import com.scpl.model.Sponsor;
import com.scpl.model.Team;
import com.scpl.model.User;
import com.scpl.model.VendorBill;
import com.scpl.model.VendorCategory;
import com.scpl.model.VendorContact;
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
import com.scpl.repo.SponsorRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.repo.VendorBillRepository;
import com.scpl.repo.VendorContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-admin restore (§10). The upload is authenticated by its GCM tag (any edit fails to decrypt),
 * cross-checked against the platform's own record of that file when one exists, and then imported into
 * a NEW society in a single transaction. It never overwrites an existing society.
 *
 * <p>Because backups hold no logins, the caller supplies the new society's founding admins, and the new
 * society starts a fresh trial in DRAFT. Row ids are remapped; a backup that refers to an unknown id
 * is rejected as inconsistent. Restored data is not re-checked against the trial's plan limits
 * (limits apply to additions, as everywhere else).
 */
@Service
@RequiredArgsConstructor
public class RestoreService {

    private static final long MAX_UNPACKED_BYTES = 200L * 1024 * 1024;
    private static final int MAX_ROWS = 200_000;

    public record Result(Long societyId, String slug, String name, Map<String, Integer> counts) {
    }

    private final BackupCrypto crypto;
    private final BackupRecordRepository records;
    private final SocietyService societyService;
    private final SubscriptionService subscriptionService;
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
    private final AuditService audit;
    private final ObjectMapper baseMapper;
    private final PlatformTransactionManager txManager;

    @Value("${scpl.backup.max-restore-bytes:268435456}")
    private long maxRestoreBytes;
    @Value("${scpl.approval.min-admins-to-activate:3}")
    private int minAdmins;

    public Result restore(User actor, MultipartFile file, String newNameOrNull, List<NewAdminRequest> admins) {
        Tenancy.requirePlatformAdmin(actor);
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Choose a .scplenc backup file");
        }
        if (file.getSize() > maxRestoreBytes) {
            throw new BusinessRuleException("The file is larger than the restore limit");
        }
        if (admins == null || admins.size() < minAdmins) {
            throw new BusinessRuleException("Backups do not contain logins. Provide at least " + minAdmins
                    + " founding admins for the restored society.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        BackupCrypto.Opened opened = crypto.open(bytes);
        verifyAgainstRecord(opened.societyId(), file.getOriginalFilename(), bytes);
        BackupDocument doc = parse(BackupCrypto.gunzip(opened.gzipped(), MAX_UNPACKED_BYTES));

        try {
            return new TransactionTemplate(txManager).execute(s -> insert(actor, doc, opened.societyId(), newNameOrNull, admins));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessRuleException("The backup contains inconsistent data and could not be restored.");
        }
    }

    /** When this platform recorded the file, its checksum must match; a foreign (but valid) file passes on its GCM tag alone. */
    private void verifyAgainstRecord(long sourceSocietyId, String uploadedName, byte[] bytes) {
        if (uploadedName == null) {
            return;
        }
        String name = uploadedName.replaceAll(".*[\\\\/]", "");
        BackupRecord known = records.findFirstBySociety_IdAndFileName(sourceSocietyId, name).orElse(null);
        if (known != null && known.getSha256() != null && !known.getSha256().equals(BackupCrypto.sha256Hex(bytes))) {
            throw new BusinessRuleException("This file does not match the checksum recorded when the backup was made.");
        }
    }

    private BackupDocument parse(byte[] json) {
        try {
            BackupDocument d = baseMapper.readValue(json, BackupDocument.class);
            if (d == null || d.society() == null || d.society().name() == null) {
                throw new BusinessRuleException("The backup has no society data");
            }
            if (d.formatVersion() > BackupDocument.CURRENT_VERSION) {
                throw new BusinessRuleException("This backup was made by a newer version of SCPL");
            }
            long rows = 0;
            for (List<?> l : java.util.Arrays.asList(d.seasons(), d.teams(), d.players(), d.matches(),
                    d.registrations(), d.lots(), d.bids(), d.sponsors(), d.awards(), d.gallery(), d.vendors(),
                    d.bills(), d.contacts())) {
                rows += l == null ? 0 : l.size();
            }
            if (rows > MAX_ROWS) {
                throw new BusinessRuleException("The backup is larger than the restore limit");
            }
            return d;
        } catch (JsonProcessingException e) {
            throw new BusinessRuleException("The backup contents are not compatible with this version");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Result insert(User actor, BackupDocument d, long sourceSocietyId, String newName, List<NewAdminRequest> admins) {
        SocietyData sd = d.society();
        SocietyRequest req = new SocietyRequest(newName != null && !newName.isBlank() ? newName.trim() : sd.name(),
                sd.slug(), sd.description(), sd.city(), sd.address(), sd.contactEmail(), sd.contactPhone());
        Society society = societyService.createWithAdmins(req, admins, actor);
        society.setLogoUrl(sd.logoUrl());
        subscriptionService.startTrial(society, null);

        Map<Long, Season> seasonMap = new HashMap<>();
        for (SeasonData x : nz(d.seasons())) {
            Season e = new Season();
            e.setSociety(society);
            e.setName(x.name());
            e.setYear(x.year());
            e.setStatus(x.status() == null ? SeasonStatus.UPCOMING : x.status());
            e.setStartDate(x.startDate());
            e.setEndDate(x.endDate());
            e.setRegistrationOpen(x.registrationOpen());
            e.setOversPerMatch(x.oversPerMatch() <= 0 ? 10 : x.oversPerMatch());
            e.setTeamPurse(x.teamPurse());
            e.setPlannedBudget(x.plannedBudget());
            e.setDescription(x.description());
            e.setCreatedAt(x.createdAt());
            seasonMap.put(x.id(), seasons.save(e));
        }

        // Players first without teams, teams next (captains are players), then the team links.
        Map<Long, Player> playerMap = new HashMap<>();
        for (PlayerData x : nz(d.players())) {
            Player e = new Player();
            e.setSociety(society);
            e.setName(x.name());
            e.setEmail(x.email());
            e.setPhone(x.phone());
            e.setDateOfBirth(x.dateOfBirth());
            e.setAddress(x.address());
            e.setFlatNumber(x.flatNumber());
            e.setRole(x.role());
            e.setBattingStyle(x.battingStyle());
            e.setBowlingStyle(x.bowlingStyle());
            e.setJerseyNumber(x.jerseyNumber());
            e.setBasePrice(x.basePrice());
            e.setPhotoUrl(x.photoUrl());
            e.setMatchesPlayed(x.matchesPlayed());
            e.setRuns(x.runs());
            e.setWickets(x.wickets());
            e.setActive(x.active());
            e.setCreatedAt(x.createdAt());
            playerMap.put(x.id(), players.save(e));
        }
        Map<Long, Team> teamMap = new HashMap<>();
        for (TeamData x : nz(d.teams())) {
            Team e = new Team();
            e.setSociety(society);
            e.setSeason(ref(seasonMap, x.seasonId(), "season"));
            e.setName(x.name());
            e.setShortName(x.shortName());
            e.setColor(x.color());
            e.setLogoUrl(x.logoUrl());
            e.setCaptain(refOrNull(playerMap, x.captainPlayerId(), "captain"));
            e.setPurseRemaining(x.purseRemaining());
            e.setCreatedAt(x.createdAt());
            teamMap.put(x.id(), teams.save(e));
        }
        for (PlayerData x : nz(d.players())) {
            playerMap.get(x.id()).setTeam(refOrNull(teamMap, x.teamId(), "team"));
        }

        for (MatchData x : nz(d.matches())) {
            Match e = new Match();
            e.setSociety(society);
            e.setSeason(ref(seasonMap, x.seasonId(), "season"));
            e.setTeamA(ref(teamMap, x.teamAId(), "team"));
            e.setTeamB(ref(teamMap, x.teamBId(), "team"));
            e.setMatchDate(x.matchDate());
            e.setVenue(x.venue());
            e.setStage(x.stage());
            e.setStatus(x.status() == null ? MatchStatus.SCHEDULED : x.status());
            e.setTeamARuns(x.teamARuns());
            e.setTeamAWickets(x.teamAWickets());
            e.setTeamAOvers(x.teamAOvers());
            e.setTeamBRuns(x.teamBRuns());
            e.setTeamBWickets(x.teamBWickets());
            e.setTeamBOvers(x.teamBOvers());
            e.setWinnerTeam(refOrNull(teamMap, x.winnerTeamId(), "team"));
            e.setResultSummary(x.resultSummary());
            e.setCreatedAt(x.createdAt());
            matches.save(e);
        }
        for (RegistrationData x : nz(d.registrations())) {
            Registration e = new Registration();
            e.setSociety(society);
            e.setSeason(ref(seasonMap, x.seasonId(), "season"));
            e.setFullName(x.fullName());
            e.setEmail(x.email());
            e.setPhone(x.phone());
            e.setDateOfBirth(x.dateOfBirth());
            e.setFlatNumber(x.flatNumber());
            e.setRole(x.role());
            e.setBattingStyle(x.battingStyle());
            e.setBowlingStyle(x.bowlingStyle());
            e.setNotes(x.notes());
            // Approval history is not restored, so a row that was mid-vote returns to the pending queue.
            e.setStatus(x.status() == null || x.status() == RegistrationStatus.AWAITING_APPROVAL
                    ? RegistrationStatus.PENDING : x.status());
            e.setPlayer(refOrNull(playerMap, x.playerId(), "player"));
            e.setCreatedAt(x.createdAt());
            registrations.save(e);
        }
        Map<Long, AuctionLot> lotMap = new HashMap<>();
        for (LotData x : nz(d.lots())) {
            AuctionLot e = new AuctionLot();
            e.setSociety(society);
            e.setSeason(ref(seasonMap, x.seasonId(), "season"));
            e.setPlayer(ref(playerMap, x.playerId(), "player"));
            e.setBasePrice(x.basePrice());
            e.setCurrentBid(x.currentBid());
            e.setCurrentBidTeam(refOrNull(teamMap, x.currentBidTeamId(), "team"));
            e.setSoldPrice(x.soldPrice());
            e.setSoldTeam(refOrNull(teamMap, x.soldTeamId(), "team"));
            e.setStatus(x.status() == null ? LotStatus.PENDING : x.status());
            e.setMode(x.mode() == null ? AuctionMode.ONLINE : x.mode());
            e.setCreatedAt(x.createdAt());
            lotMap.put(x.id(), lots.save(e));
        }
        for (BidData x : nz(d.bids())) {
            AuctionBid e = new AuctionBid();
            e.setLot(ref(lotMap, x.lotId(), "lot"));
            e.setTeam(ref(teamMap, x.teamId(), "team"));
            e.setAmount(x.amount());
            e.setBidAt(x.bidAt());
            bids.save(e);
        }
        for (SponsorData x : nz(d.sponsors())) {
            Sponsor e = new Sponsor();
            e.setSociety(society);
            e.setSeason(refOrNull(seasonMap, x.seasonId(), "season"));
            e.setName(x.name());
            e.setTier(x.tier());
            e.setLogoUrl(x.logoUrl());
            e.setWebsite(x.website());
            e.setAmount(x.amount());
            e.setActive(x.active());
            e.setCreatedAt(x.createdAt());
            sponsors.save(e);
        }
        for (AwardData x : nz(d.awards())) {
            Award e = new Award();
            e.setSociety(society);
            e.setSeason(refOrNull(seasonMap, x.seasonId(), "season"));
            e.setPlayer(refOrNull(playerMap, x.playerId(), "player"));
            e.setTeam(refOrNull(teamMap, x.teamId(), "team"));
            e.setTitle(x.title());
            e.setCategory(x.category());
            e.setDescription(x.description());
            e.setImageUrl(x.imageUrl());
            e.setCreatedAt(x.createdAt());
            awards.save(e);
        }
        for (GalleryData x : nz(d.gallery())) {
            GalleryImage e = new GalleryImage();
            e.setSociety(society);
            e.setSeason(refOrNull(seasonMap, x.seasonId(), "season"));
            e.setTitle(x.title());
            e.setImageUrl(x.imageUrl());
            e.setCaption(x.caption());
            e.setCreatedAt(x.createdAt());
            gallery.save(e);
        }
        Map<Long, VendorContact> vendorMap = new HashMap<>();
        for (VendorData x : nz(d.vendors())) {
            VendorContact e = new VendorContact();
            e.setSociety(society);
            e.setName(x.name());
            e.setCategory(x.category() == null ? VendorCategory.OTHER : x.category());
            e.setContactPerson(x.contactPerson());
            e.setPhone(x.phone());
            e.setWhatsappNumber(x.whatsappNumber());
            e.setEmail(x.email());
            e.setAddress(x.address());
            e.setCity(x.city());
            e.setWebsite(x.website());
            e.setRating(x.rating());
            e.setNotes(x.notes());
            e.setActive(x.active());
            e.setCreatedAt(x.createdAt());
            vendorMap.put(x.id(), vendors.save(e));
        }
        for (BillData x : nz(d.bills())) {
            VendorBill e = new VendorBill();
            e.setSociety(society);
            e.setVendor(ref(vendorMap, x.vendorId(), "vendor"));
            e.setSeason(refOrNull(seasonMap, x.seasonId(), "season"));
            e.setTitle(x.title());
            e.setAmount(x.amount());
            e.setBillDate(x.billDate());
            e.setPaidDate(x.paidDate());
            e.setStatus(x.status() == null ? BillStatus.PENDING : x.status());
            e.setDescription(x.description());
            e.setAttachmentUrl(x.attachmentUrl());
            e.setCreatedAt(x.createdAt());
            bills.save(e);
        }
        // The new admins already have contact cards; these are the old society's committee cards.
        for (ContactData x : nz(d.contacts())) {
            AdminContact e = new AdminContact();
            e.setSociety(society);
            e.setName(x.name());
            e.setRoleTitle(x.roleTitle());
            e.setPhone(x.phone());
            e.setWhatsappNumber(x.whatsappNumber());
            e.setEmail(x.email());
            e.setCreatedAt(x.createdAt());
            contacts.save(e);
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("seasons", nz(d.seasons()).size());
        counts.put("teams", nz(d.teams()).size());
        counts.put("players", nz(d.players()).size());
        counts.put("matches", nz(d.matches()).size());
        counts.put("registrations", nz(d.registrations()).size());
        counts.put("auctionLots", nz(d.lots()).size());
        counts.put("vendors", nz(d.vendors()).size());
        counts.put("bills", nz(d.bills()).size());
        audit.record(actor, society.getId(), AuditActions.BACKUP_RESTORED, "SOCIETY", society.getId(), null,
                Map.of("sourceSocietyId", sourceSocietyId, "rows", counts));
        return new Result(society.getId(), society.getSlug(), society.getName(), counts);
    }

    // ------------------------------------------------------------------ helpers

    private static <T> List<T> nz(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private static <T> T ref(Map<Long, T> map, Long id, String what) {
        T v = id == null ? null : map.get(id);
        if (v == null) {
            throw new BusinessRuleException("The backup is inconsistent: it refers to an unknown " + what);
        }
        return v;
    }

    private static <T> T refOrNull(Map<Long, T> map, Long id, String what) {
        return id == null ? null : ref(map, id, what);
    }
}