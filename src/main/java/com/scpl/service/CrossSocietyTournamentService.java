package com.scpl.service;

import com.scpl.dto.TournamentViews.ParticipantView;
import com.scpl.dto.TournamentViews.RosterEntry;
import com.scpl.dto.TournamentViews.TournamentView;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.exception.QuotaExceededException;
import com.scpl.exception.TenantAccessException;
import com.scpl.model.AdminApproval;
import com.scpl.model.ApprovalEntityType;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.CrossSocietyTournament;
import com.scpl.model.Society;
import com.scpl.model.SocietyStatus;
import com.scpl.model.TournamentStatus;
import com.scpl.model.User;
import com.scpl.repo.AdminApprovalRepository;
import com.scpl.repo.CrossSocietyTournamentRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.util.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-society tournaments (§6), gated by feature.cross-society.enabled and the Premium plan.
 *
 * <p>A society "accepts" a tournament by passing a TOURNAMENT_JOIN approval inside its own admin
 * team, so the usual approval rules apply with no extra vote tables. participatingSocieties holds
 * the host plus everyone invited; acceptance is read from each society's latest approval.
 * The aggregate roster exposes name, role and stats only: it reads through a closed projection,
 * so PII is never loaded.
 */
@Service
@RequiredArgsConstructor
public class CrossSocietyTournamentService {

    private static final Set<TournamentStatus> OPEN_FOR_ACCEPTANCE =
            EnumSet.of(TournamentStatus.PROPOSED, TournamentStatus.APPROVING);

    private final CrossSocietyTournamentRepository tournaments;
    private final SocietyRepository societies;
    private final PlayerRepository players;
    private final AdminApprovalRepository approvalRepo;
    private final AdminApprovalService approvals;
    private final QuotaService quota;
    private final NotificationService notifications;
    private final AuditService audit;

    @Value("${feature.cross-society.enabled:false}")
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    /** Flag off means the feature does not exist: a 404 rather than a 403 that advertises it. */
    private void assertEnabled() {
        if (!enabled) {
            throw new NotFoundException("Page not found");
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<TournamentView> list(User actor) {
        assertEnabled();
        if (actor != null && actor.isPlatformAdmin()) {
            return tournaments.findAllByOrderByCreatedAtDesc().stream().map(t -> toView(t, null)).toList();
        }
        Tenancy.requireAdminOf(actor, actor == null ? null : actor.getSocietyId());
        return tournaments.findInvolving(actor.getSocietyId()).stream()
                .map(t -> toView(t, actor.getSocietyId())).toList();
    }

    @Transactional(readOnly = true)
    public TournamentView get(User actor, Long tournamentId) {
        assertEnabled();
        CrossSocietyTournament t = load(tournamentId);
        Long viewerSociety = actor != null && actor.isSocietyAdmin() ? actor.getSocietyId() : null;
        requireInvolvedOrPlatform(actor, t);
        return toView(t, viewerSociety);
    }

    /** Active Premium societies other than the host, for the invitation picker. */
    @Transactional(readOnly = true)
    public List<ParticipantView> invitable(User actor, Long hostSocietyId) {
        assertEnabled();
        Tenancy.requireAdminOf(actor, hostSocietyId);
        return societies.findByStatusOrderByNameAsc(SocietyStatus.ACTIVE).stream()
                .filter(s -> !s.getId().equals(hostSocietyId))
                .filter(s -> quota.limitsFor(s.getId()).crossSociety())
                .map(s -> new ParticipantView(s.getId(), s.getName(), false, false)).toList();
    }

    /** Stats-only roster of the societies that accepted. Platform admin and the host's admins only. */
    @Transactional(readOnly = true)
    public List<RosterEntry> roster(User actor, Long tournamentId) {
        assertEnabled();
        CrossSocietyTournament t = load(tournamentId);
        boolean platform = actor != null && actor.isPlatformAdmin();
        boolean host = actor != null && actor.isSocietyAdmin() && t.getHostSociety().getId().equals(actor.getSocietyId());
        if (!platform && !host) {
            throw new TenantAccessException("Only the host society and platform admins can see the roster");
        }
        List<RosterEntry> rows = new ArrayList<>();
        for (Society s : t.getParticipatingSocieties()) {
            if (!isAccepted(s.getId(), t.getId())) {
                continue;
            }
            players.findRosterBySociety_IdAndActiveTrueOrderByNameAsc(s.getId()).forEach(p -> rows.add(
                    new RosterEntry(s.getName(), p.getName(), p.getRole() == null ? "" : p.getRole().getLabel(),
                            p.getMatchesPlayed(), p.getRuns(), p.getWickets())));
        }
        rows.sort(Comparator.comparing(RosterEntry::societyName).thenComparing(RosterEntry::playerName));
        return rows;
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public CrossSocietyTournament propose(User actor, Long hostSocietyId, String name, LocalDate start, LocalDate end,
                                          String description, Collection<Long> invitedIds) {
        assertEnabled();
        Tenancy.requireAdminOf(actor, hostSocietyId);
        requirePremium(hostSocietyId, "Hosting a tournament");
        if (Text.clean(name) == null) {
            throw new BusinessRuleException("The tournament needs a name");
        }
        if (start != null && end != null && end.isBefore(start)) {
            throw new BusinessRuleException("The end date cannot be before the start date");
        }
        Society host = societies.findById(hostSocietyId).orElseThrow(() -> new NotFoundException("Society not found"));

        Set<Long> ids = new LinkedHashSet<>(invitedIds == null ? List.of() : invitedIds);
        ids.remove(hostSocietyId);
        if (ids.isEmpty()) {
            throw new BusinessRuleException("Invite at least one other society");
        }
        Set<Society> participants = new LinkedHashSet<>();
        participants.add(host);
        for (Long id : ids) {
            Society s = societies.findById(id).filter(x -> x.getStatus() == SocietyStatus.ACTIVE)
                    .filter(x -> quota.limitsFor(x.getId()).crossSociety())
                    // One message for every failure so this cannot be used to probe other tenants' plans.
                    .orElseThrow(() -> new BusinessRuleException("One of the selected societies cannot be invited"));
            participants.add(s);
        }
        CrossSocietyTournament t = new CrossSocietyTournament();
        t.setName(name.trim());
        t.setHostSociety(host);
        t.setStartDate(start);
        t.setEndDate(end);
        t.setDescription(Text.clean(description));
        t.setStatus(TournamentStatus.PROPOSED);
        t.setParticipatingSocieties(participants);
        t = tournaments.save(t);

        // The host's own admin team must also agree to host.
        approvals.raise(actor, hostSocietyId, ApprovalEntityType.TOURNAMENT_JOIN, t.getId(), null,
                "Host the tournament '" + t.getName() + "'", null);
        for (Society s : participants) {
            if (!s.getId().equals(hostSocietyId)) {
                notifications.notifyAdmins(s.getId(), null, "Tournament invitation",
                        host.getName() + " invited your society to '" + t.getName() + "'.", "/society-admin#tournaments");
            }
        }
        audit.record(actor, hostSocietyId, AuditActions.TOURNAMENT_PROPOSED, "TOURNAMENT", t.getId(), null,
                Map.of("name", t.getName(), "invited", ids.size()));
        return t;
    }

    /** An invited society starts its own approval vote on joining. */
    @Transactional
    public AdminApproval requestJoin(User actor, Long societyId, Long tournamentId) {
        assertEnabled();
        Tenancy.requireAdminOf(actor, societyId);
        CrossSocietyTournament t = load(tournamentId);
        if (!isParticipant(t, societyId)) {
            throw new NotFoundException("Tournament not found");
        }
        if (!OPEN_FOR_ACCEPTANCE.contains(t.getStatus())) {
            throw new BusinessRuleException("This tournament is no longer accepting responses");
        }
        requirePremium(societyId, "Joining a tournament");
        approvalRepo.findFirstBySociety_IdAndEntityTypeAndEntityIdOrderByRequestedAtDesc(
                societyId, ApprovalEntityType.TOURNAMENT_JOIN, tournamentId).ifPresent(prev -> {
            if (prev.getStatus() == ApprovalStatus.PENDING || prev.getStatus() == ApprovalStatus.APPROVED) {
                throw new BusinessRuleException("Your society has already responded to this tournament");
            }
        });
        return approvals.raise(actor, societyId, ApprovalEntityType.TOURNAMENT_JOIN, tournamentId, null,
                "Join the tournament '" + t.getName() + "' hosted by " + t.getHostSociety().getName(), null);
    }

    /** Declining needs no vote: it only removes your own society from someone else's tournament. */
    @Transactional
    public void decline(User actor, Long societyId, Long tournamentId) {
        assertEnabled();
        Tenancy.requireAdminOf(actor, societyId);
        CrossSocietyTournament t = load(tournamentId);
        if (t.getHostSociety().getId().equals(societyId)) {
            throw new BusinessRuleException("The host cannot decline. Cancel the tournament instead.");
        }
        if (!isParticipant(t, societyId) || !OPEN_FOR_ACCEPTANCE.contains(t.getStatus())) {
            throw new BusinessRuleException("There is nothing to decline");
        }
        removeParticipant(t, societyId, "declined");
        audit.record(actor, societyId, AuditActions.TOURNAMENT_DECLINED, "TOURNAMENT", tournamentId, null, null);
    }

    @Transactional
    public CrossSocietyTournament setStatus(User actor, Long hostSocietyId, Long tournamentId, TournamentStatus next) {
        assertEnabled();
        Tenancy.requireAdminOf(actor, hostSocietyId);
        CrossSocietyTournament t = load(tournamentId);
        if (!t.getHostSociety().getId().equals(hostSocietyId)) {
            throw new TenantAccessException("Only the host society can change the tournament status");
        }
        applyStatus(actor, t, next);
        return t;
    }

    /** Platform admins manage tournaments centrally; cancelling is the one action they can take on a society's behalf. */
    @Transactional
    public CrossSocietyTournament cancelAsPlatform(User actor, Long tournamentId) {
        assertEnabled();
        Tenancy.requirePlatformAdmin(actor);
        CrossSocietyTournament t = load(tournamentId);
        applyStatus(actor, t, TournamentStatus.CANCELLED);
        return t;
    }

    // ------------------------------------------------------------------ approval outcome

    @EventListener
    @Transactional
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        AdminApproval a = event.approval();
        if (!ApprovalEntityType.TOURNAMENT_JOIN.equals(a.getEntityType())) {
            return;
        }
        CrossSocietyTournament t = tournaments.findWithSocietiesById(a.getEntityId()).orElse(null);
        if (t == null) {
            return;
        }
        Long societyId = a.getSocietyId();
        switch (a.getStatus()) {
            case APPROVED -> {
                audit.record(event.decidedBy(), societyId, AuditActions.TOURNAMENT_JOINED, "TOURNAMENT", t.getId(), null,
                        Map.of("name", t.getName()));
                reevaluate(t);
            }
            case REJECTED -> {
                if (t.getHostSociety().getId().equals(societyId)) {
                    // The host's own admins refused to host.
                    t.setStatus(TournamentStatus.CANCELLED);
                    tournaments.save(t);
                    notifyParticipants(t, "Tournament cancelled", "'" + t.getName() + "' was not approved by its host society.");
                } else {
                    removeParticipant(t, societyId, "voted not to join");
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ internals

    private CrossSocietyTournament load(Long id) {
        return tournaments.findWithSocietiesById(id).orElseThrow(() -> new NotFoundException("Tournament not found"));
    }

    private static boolean isParticipant(CrossSocietyTournament t, Long societyId) {
        return t.getParticipatingSocieties().stream().anyMatch(s -> s.getId().equals(societyId));
    }

    private void requireInvolvedOrPlatform(User actor, CrossSocietyTournament t) {
        if (actor != null && actor.isPlatformAdmin()) {
            return;
        }
        Tenancy.requireAdminOf(actor, actor == null ? null : actor.getSocietyId());
        if (!isParticipant(t, actor.getSocietyId())) {
            throw new NotFoundException("Tournament not found");
        }
    }

    private void requirePremium(Long societyId, String what) {
        if (!quota.limitsFor(societyId).crossSociety()) {
            throw new QuotaExceededException(what + " is included in the Premium plan.");
        }
    }

    private boolean isAccepted(Long societyId, Long tournamentId) {
        return approvalRepo.findFirstBySociety_IdAndEntityTypeAndEntityIdOrderByRequestedAtDesc(
                        societyId, ApprovalEntityType.TOURNAMENT_JOIN, tournamentId)
                .map(a -> a.getStatus() == ApprovalStatus.APPROVED).orElse(false);
    }

    private void removeParticipant(CrossSocietyTournament t, Long societyId, String how) {
        Society leaving = t.getParticipatingSocieties().stream().filter(s -> s.getId().equals(societyId))
                .findFirst().orElse(null);
        if (leaving == null) {
            return;
        }
        t.getParticipatingSocieties().remove(leaving);
        tournaments.save(t);
        notifications.notifyAdmins(t.getHostSociety().getId(), null, "Society left tournament",
                leaving.getName() + " " + how + " '" + t.getName() + "'.", "/society-admin#tournaments");
        reevaluate(t);
    }

    /** Derives PROPOSED / APPROVING / APPROVED from how many participants have accepted. */
    private void reevaluate(CrossSocietyTournament t) {
        if (!OPEN_FOR_ACCEPTANCE.contains(t.getStatus()) && t.getStatus() != TournamentStatus.APPROVED) {
            return;
        }
        if (t.getParticipatingSocieties().size() < 2) {
            t.setStatus(TournamentStatus.CANCELLED);
            tournaments.save(t);
            notifyParticipants(t, "Tournament cancelled", "'" + t.getName() + "' no longer has enough societies.");
            return;
        }
        long accepted = t.getParticipatingSocieties().stream().filter(s -> isAccepted(s.getId(), t.getId())).count();
        TournamentStatus before = t.getStatus();
        t.setStatus(accepted == t.getParticipatingSocieties().size() ? TournamentStatus.APPROVED
                : accepted > 0 ? TournamentStatus.APPROVING : TournamentStatus.PROPOSED);
        tournaments.save(t);
        if (t.getStatus() == TournamentStatus.APPROVED && before != TournamentStatus.APPROVED) {
            notifyParticipants(t, "Tournament approved", "Every society has accepted '" + t.getName() + "'.");
        }
    }

    private void applyStatus(User actor, CrossSocietyTournament t, TournamentStatus next) {
        TournamentStatus cur = t.getStatus();
        boolean legal = switch (next) {
            case IN_PROGRESS -> cur == TournamentStatus.APPROVED;
            case COMPLETED -> cur == TournamentStatus.IN_PROGRESS;
            case CANCELLED -> cur != TournamentStatus.COMPLETED && cur != TournamentStatus.CANCELLED;
            default -> false;
        };
        if (!legal) {
            throw new BusinessRuleException("A tournament cannot move from " + cur.getLabel() + " to " + next.getLabel());
        }
        t.setStatus(next);
        tournaments.save(t);
        audit.record(actor, t.getHostSociety().getId(), AuditActions.TOURNAMENT_STATUS_CHANGED, "TOURNAMENT", t.getId(),
                Map.of("status", cur.name()), Map.of("status", next.name()));
        notifyParticipants(t, "Tournament update", "'" + t.getName() + "' is now " + next.getLabel() + ".");
    }

    private void notifyParticipants(CrossSocietyTournament t, String title, String message) {
        for (Society s : t.getParticipatingSocieties()) {
            notifications.notifyAdmins(s.getId(), null, title, message, "/society-admin#tournaments");
        }
    }

    private TournamentView toView(CrossSocietyTournament t, Long viewerSocietyId) {
        Long hostId = t.getHostSociety().getId();
        List<ParticipantView> parts = t.getParticipatingSocieties().stream()
                .sorted(Comparator.comparing(Society::getName))
                .map(s -> new ParticipantView(s.getId(), s.getName(), s.getId().equals(hostId), isAccepted(s.getId(), t.getId())))
                .toList();
        boolean viewerAccepted = viewerSocietyId != null && isAccepted(viewerSocietyId, t.getId());
        boolean pendingVote = viewerSocietyId != null && approvalRepo
                .findFirstBySociety_IdAndEntityTypeAndEntityIdOrderByRequestedAtDesc(
                        viewerSocietyId, ApprovalEntityType.TOURNAMENT_JOIN, t.getId())
                .map(a -> a.getStatus() == ApprovalStatus.PENDING).orElse(false);
        boolean canJoin = viewerSocietyId != null && !viewerSocietyId.equals(hostId) && isParticipant(t, viewerSocietyId)
                && !viewerAccepted && !pendingVote && OPEN_FOR_ACCEPTANCE.contains(t.getStatus());
        return new TournamentView(t.getId(), t.getName(), hostId, t.getHostSociety().getName(), t.getStartDate(),
                t.getEndDate(), t.getStatus(), t.getStatus().getLabel(), t.getDescription(), parts,
                viewerSocietyId != null && viewerSocietyId.equals(hostId), canJoin, viewerAccepted);
    }
}