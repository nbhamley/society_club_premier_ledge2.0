package com.scpl.service;

import com.scpl.dto.AdminViews.ApprovalView;
import com.scpl.dto.AdminViews.ApprovalsPageView;
import com.scpl.dto.AdminViews.AuditRow;
import com.scpl.dto.AdminViews.AwardRow;
import com.scpl.dto.AdminViews.BillRow;
import com.scpl.dto.AdminViews.ConsoleView;
import com.scpl.dto.AdminViews.GalleryRow;
import com.scpl.dto.AdminViews.Kpis;
import com.scpl.dto.AdminViews.LotRow;
import com.scpl.dto.AdminViews.MatchRow;
import com.scpl.dto.AdminViews.PlayerRow;
import com.scpl.dto.AdminViews.RegistrationRow;
import com.scpl.dto.AdminViews.SeasonRow;
import com.scpl.dto.AdminViews.SponsorRow;
import com.scpl.dto.AdminViews.TeamRow;
import com.scpl.dto.AdminViews.VoteRow;
import com.scpl.dto.StandingRow;
import com.scpl.model.AdminApproval;
import com.scpl.model.AdminApprovalVote;
import com.scpl.model.AuctionLot;
import com.scpl.model.AuditLog;
import com.scpl.model.Award;
import com.scpl.model.GalleryImage;
import com.scpl.model.Match;
import com.scpl.model.Player;
import com.scpl.model.Registration;
import com.scpl.model.Season;
import com.scpl.model.Society;
import com.scpl.model.Sponsor;
import com.scpl.model.Subscription;
import com.scpl.model.SubscriptionStatus;
import com.scpl.model.Team;
import com.scpl.model.User;
import com.scpl.model.VendorBill;
import com.scpl.model.Vote;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.scpl.dto.AdminViews.SocietyDetails;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds every society-admin page model inside one read-only transaction, so templates never touch a lazy
 * association and never receive an entity. Every public method re-checks Tenancy.requireAdminOf.
 *
 * <p>Note for maintainers: never catch an exception thrown by another @Transactional service inside these
 * methods. The inner call marks the shared transaction rollback-only, and this one would then fail at commit.
 * Check a condition first (for example QuotaService.limitsFor) instead.
 */
@Service
@RequiredArgsConstructor
public class AdminConsoleService {

    private static final int AUDIT_PAGE_SIZE = 25;
    private static final int HISTORY_LIMIT = 100;
    private static final int DETAIL_LIMIT = 200;

    private static final Map<String, String> TYPE_LABELS = Map.of(
            "REGISTRATION", "Approve a registration",
            "REGISTRATION_BATCH", "Approve registrations (batch)",
            "PLAYER_DROP", "Drop a player",
            "BULK_IMPORT", "Bulk import",
            "SEASON_DELETE", "Delete a season",
            "ADMIN_INVITE", "Invite an admin",
            "ADMIN_REMOVE", "Remove an admin",
            "TOURNAMENT_JOIN", "Tournament participation");

    private final SocietyService societies;
    private final SeasonService seasons;
    private final TeamService teams;
    private final PlayerService players;
    private final RegistrationService registrations;
    private final AuctionService auctions;
    private final MatchService matches;
    private final ContentService content;
    private final BillService bills;
    private final AdminApprovalService approvals;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    // ------------------------------------------------------------------ console

    @Transactional(readOnly = true)
    public ConsoleView console(User actor, Long societyId, Long seasonParam) {
        Tenancy.requireAdminOf(actor, societyId);
        Society society = societies.get(societyId);
        List<Season> seasonList = seasons.list(societyId);
        Season selected = pick(societyId, seasonList, seasonParam);
        Long selectedId = selected == null ? null : selected.getId();

        List<TeamRow> teamRows = teams.forSociety(societyId).stream().map(AdminConsoleService::toRow).toList();
        List<Player> playerList = players.listForAdmin(actor, societyId);
        List<Match> matchList = matches.forSociety(societyId);

        Optional<Match> next = matches.nextFixture(societyId);
        Subscription sub = subscriptions.current(societyId).orElse(null);
        Long trialLeft = sub != null && sub.getStatus() == SubscriptionStatus.TRIAL && sub.getExpiresAt() != null
                ? Math.max(0, ChronoUnit.DAYS.between(LocalDateTime.now(), sub.getExpiresAt())) : null;
        Kpis kpis = new Kpis(teamRows.size(), playerList.stream().filter(p -> Boolean.TRUE.equals(p.getActive())).count(),
                matchList.size(), approvals.pendingCount(societyId), approvals.awaitingVoteFrom(actor).size(),
                sub == null ? null : sub.getPlan().getLabel(), sub == null ? null : sub.getStatus().getLabel(), trialLeft,
                next.map(m -> m.getTeamA().getName() + " vs " + m.getTeamB().getName()).orElse(null),
                next.map(Match::getMatchDate).orElse(null), subscriptions.isReadOnly(societyId));

        List<RegistrationRow> regRows = registrations.list(actor, societyId, null).stream()
                .map(AdminConsoleService::toRow).toList();
        List<AuctionLot> lotList = selectedId == null ? List.of() : auctions.lotsForSeason(societyId, selectedId);
        AuctionLot live = selectedId == null ? null : auctions.liveLot(societyId, selectedId);
        List<StandingRow> standings = selectedId == null ? List.of() : matches.standings(societyId, selectedId);

        return new ConsoleView(society.getName(), society.getSlug(), society.getStatus().getLabel(), society.getAdminCount(),
                kpis, selectedId, selected == null ? null : selected.getName(),
                seasonList.stream().map(AdminConsoleService::toRow).toList(), teamRows,
                teamRows.stream().filter(t -> t.seasonId().equals(selectedId)).toList(),
                playerList.stream().map(AdminConsoleService::toRow).toList(), regRows,
                lotList.stream().map(AdminConsoleService::toRow).toList(), live == null ? null : toRow(live),
                matchList.stream().map(AdminConsoleService::toRow).toList(), standings,
                content.sponsors(actor, societyId).stream().map(AdminConsoleService::toRow).toList(),
                content.awards(actor, societyId).stream().map(AdminConsoleService::toRow).toList(),
                content.gallery(actor, societyId).stream().map(AdminConsoleService::toRow).toList());
    }

    @Transactional(readOnly = true)
    public List<SeasonRow> seasonRows(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        return seasons.list(societyId).stream().map(AdminConsoleService::toRow).toList();
    }

    /** For the captain bid page: the live lot of one season, scoped to the caller's own society. */
    @Transactional(readOnly = true)
    public LotRow liveLot(Long societyId, Long seasonId) {
        AuctionLot lot = auctions.liveLot(societyId, seasonId);
        return lot == null ? null : toRow(lot);
    }

    @Transactional(readOnly = true)
    public List<BillRow> billRows(User actor, Long societyId, Long seasonIdOrNull) {
        return bills.list(actor, societyId, seasonIdOrNull).stream().map(AdminConsoleService::toRow).toList();
    }

    // ------------------------------------------------------------------ approvals page

    @Transactional(readOnly = true)
    public ApprovalsPageView approvalsPage(User actor, Long societyId, int page) {
        Tenancy.requireAdminOf(actor, societyId);
        List<AdminApproval> all = approvals.history(societyId);
        List<ApprovalView> pending = all.stream().filter(AdminApproval::isPending).map(a -> toView(a, actor)).toList();
        List<ApprovalView> done = all.stream().filter(a -> !a.isPending()).limit(HISTORY_LIMIT)
                .map(a -> toView(a, actor)).toList();
        int index = Math.max(0, page);
        Page<AuditLog> logs = audit.forSociety(societyId, PageRequest.of(index, AUDIT_PAGE_SIZE));
        List<AuditRow> rows = logs.getContent().stream().map(l -> new AuditRow(l.getTimestamp(),
                l.getUser() == null ? "system" : name(l.getUser()), l.getAction(), l.getEntityType(), l.getEntityId(),
                cut(l.getNewValue()))).toList();
        return new ApprovalsPageView(pending, done, rows, index, Math.max(1, logs.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public SocietyDetails details(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        Society s = societies.get(societyId);
        return new SocietyDetails(s.getName(), s.getSlug(), s.getDescription(), s.getCity(), s.getAddress(),
                s.getContactEmail(), s.getContactPhone());
    }

    private ApprovalView toView(AdminApproval a, User viewer) {
        boolean mine = a.getRequestedBy().getId().equals(viewer.getId());
        Optional<AdminApprovalVote> own = a.getVotes().stream()
                .filter(v -> v.getVoter().getId().equals(viewer.getId())).findFirst();
        List<VoteRow> votes = a.getVotes().stream().sorted(Comparator.comparing(AdminApprovalVote::getVotedAt))
                .map(v -> new VoteRow(name(v.getVoter()), v.getVote().getLabel(), v.getVotedAt(), v.getComment())).toList();
        int items = a.getEntityIds().isEmpty() ? 1 : a.getEntityIds().size();
        return new ApprovalView(a.getId(), a.getEntityType(), TYPE_LABELS.getOrDefault(a.getEntityType(), a.getEntityType()),
                a.getNotes(), name(a.getRequestedBy()), a.getRequestedAt(), a.getStatus(), a.getStatus().getLabel(),
                a.getApprovalsRequired(), a.getEligibleVoters(), a.countVotes(Vote.APPROVE), a.countVotes(Vote.REJECT),
                items, mine, a.isPending() && !mine && own.isEmpty(), a.isPending() && mine,
                own.map(v -> v.getVote().getLabel()).orElse(null), a.getDecidedAt(), a.getDecisionNotes(), votes);
    }

    // ------------------------------------------------------------------ mapping

    private static Season pick(Long societyId, List<Season> list, Long param) {
        if (param != null) {
            Optional<Season> hit = list.stream().filter(s -> s.getId().equals(param)).findFirst();
            if (hit.isPresent()) {
                return hit.get();
            }
        }
        Season nowOrNull = list.stream().filter(s -> !s.isCompleted()).findFirst().orElse(null);
        return nowOrNull != null ? nowOrNull : list.isEmpty() ? null : list.get(0);
    }

    private static String name(User u) {
        return u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > DETAIL_LIMIT ? s.substring(0, DETAIL_LIMIT) + "…" : s;
    }

    private static SeasonRow toRow(Season s) {
        return new SeasonRow(s.getId(), s.getName(), s.getYear(), s.getStatus(), s.getStatus().getLabel(), s.getStartDate(),
                s.getEndDate(), Boolean.TRUE.equals(s.getRegistrationOpen()), s.getOversPerMatch(), s.getTeamPurse(),
                s.getPlannedBudget(), s.getDescription());
    }

    private static TeamRow toRow(Team t) {
        return new TeamRow(t.getId(), t.getSeasonId(), t.getSeason().getName(), t.getName(), t.getShortName(), t.getColor(),
                t.getLogoUrl(), t.getCaptain() == null ? null : t.getCaptain().getId(),
                t.getCaptain() == null ? null : t.getCaptain().getName(), t.getPurseRemaining());
    }

    private static PlayerRow toRow(Player p) {
        return new PlayerRow(p.getId(), p.getName(), p.getEmail(), p.getPhone(), p.getDateOfBirth(), p.getAddress(),
                p.getFlatNumber(), p.getRole(), p.getBattingStyle(), p.getBowlingStyle(), p.getJerseyNumber(),
                p.getBasePrice(), p.getPhotoUrl(), p.getTeamId(), p.getTeam() == null ? null : p.getTeam().getName(),
                p.getMatchesPlayed(), p.getRuns(), p.getWickets(), Boolean.TRUE.equals(p.getActive()));
    }

    private static RegistrationRow toRow(Registration r) {
        return new RegistrationRow(r.getId(), r.getSeason().getName(), r.getFullName(), r.getEmail(), r.getPhone(),
                r.getRole() == null ? null : r.getRole().getLabel(), r.getStatus(), r.getStatus().getLabel(),
                r.getStatus() == com.scpl.model.RegistrationStatus.PENDING,
                r.getApproval() == null ? null : r.getApproval().getId(), r.getCreatedAt());
    }

    private static LotRow toRow(AuctionLot l) {
        return new LotRow(l.getId(), l.getPlayer().getId(), l.getPlayer().getName(), l.getBasePrice(), l.getCurrentBid(),
                l.getCurrentBidTeam() == null ? null : l.getCurrentBidTeam().getName(), l.getSoldPrice(),
                l.getSoldTeam() == null ? null : l.getSoldTeam().getName(), l.getStatus(), l.getStatus().getLabel(),
                l.getMode(), l.getMode().getLabel(), l.isClosed());
    }

    private static MatchRow toRow(Match m) {
        return new MatchRow(m.getId(), m.getSeason().getId(), m.getSeason().getName(), m.getTeamA().getId(),
                m.getTeamB().getId(), m.getTeamA().getName(), m.getTeamB().getName(), m.getMatchDate(), m.getVenue(),
                m.getStage(), m.getStage() == null ? null : m.getStage().getLabel(), m.getStatus(),
                m.getStatus().getLabel(), m.getTeamARuns(), m.getTeamAWickets(), m.getTeamAOvers(), m.getTeamBRuns(),
                m.getTeamBWickets(), m.getTeamBOvers(), m.getWinnerTeam() == null ? null : m.getWinnerTeam().getName(),
                m.getResultSummary());
    }

    private static SponsorRow toRow(Sponsor s) {
        return new SponsorRow(s.getId(), s.getName(), s.getTier(), s.getTier() == null ? null : s.getTier().getLabel(),
                s.getLogoUrl(), s.getWebsite(), s.getAmount(), Boolean.TRUE.equals(s.getActive()),
                s.getSeason() == null ? null : s.getSeason().getId(), s.getSeason() == null ? null : s.getSeason().getName());
    }

    private static AwardRow toRow(Award a) {
        return new AwardRow(a.getId(), a.getTitle(), a.getCategory(), a.getCategory() == null ? null : a.getCategory().getLabel(),
                a.getDescription(), a.getImageUrl(), a.getSeason() == null ? null : a.getSeason().getId(),
                a.getSeason() == null ? null : a.getSeason().getName(), a.getPlayer() == null ? null : a.getPlayer().getId(),
                a.getPlayer() == null ? null : a.getPlayer().getName(), a.getTeam() == null ? null : a.getTeam().getId(),
                a.getTeam() == null ? null : a.getTeam().getName());
    }

    private static GalleryRow toRow(GalleryImage g) {
        return new GalleryRow(g.getId(), g.getTitle(), g.getImageUrl(), g.getCaption(),
                g.getSeason() == null ? null : g.getSeason().getId(), g.getSeason() == null ? null : g.getSeason().getName());
    }

    private static BillRow toRow(VendorBill b) {
        return new BillRow(b.getId(), b.getTitle(), b.getVendor().getId(), b.getVendor().getName(),
                b.getVendor().getCategory().getLabel(), b.getSeason() == null ? null : b.getSeason().getId(),
                b.getSeason() == null ? null : b.getSeason().getName(), b.getAmount(), b.getBillDate(), b.getPaidDate(),
                b.getStatus(), b.getStatus().getLabel(), b.getDescription(), b.getAttachmentUrl());
    }
}