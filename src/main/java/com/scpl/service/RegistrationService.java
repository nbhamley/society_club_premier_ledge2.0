package com.scpl.service;

import com.scpl.dto.RegistrationRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AdminApproval;
import com.scpl.model.ApprovalEntityType;
import com.scpl.model.AuctionLot;
import com.scpl.model.Player;
import com.scpl.model.Registration;
import com.scpl.model.RegistrationStatus;
import com.scpl.model.Season;
import com.scpl.model.User;
import com.scpl.repo.AuctionLotRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.RegistrationRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.UserRepository;
import com.scpl.util.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Public sign-up, then the approval gate. Nothing becomes a Player or an AuctionLot until the
 * approval completes (§6). Bulk approve raises exactly ONE approval for the whole batch.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final Set<RegistrationStatus> BLOCKS_RESIGNUP = EnumSet.of(
            RegistrationStatus.PENDING, RegistrationStatus.AWAITING_APPROVAL, RegistrationStatus.APPROVED);

    private final RegistrationRepository registrations;
    private final SeasonRepository seasons;
    private final PlayerRepository players;
    private final AuctionLotRepository lots;
    private final UserRepository users;
    private final QuotaService quota;
    private final AdminApprovalService approvals;
    private final NotificationService notifications;
    private final AuditService audit;

    @Value("${scpl.auction.default-base-price:1000}")
    private double defaultBasePrice;

    // ------------------------------------------------------------------ public submission

    @Transactional
    public Registration submit(Long societyId, Long seasonId, RegistrationRequest r) {
        Season season = seasons.findByIdAndSociety_Id(seasonId, societyId)
                .orElseThrow(() -> new NotFoundException("Season not found"));
        if (!Boolean.TRUE.equals(season.getRegistrationOpen())) {
            throw new BusinessRuleException("Registration is not open for this season");
        }
        String email = r.email().trim();
        if (registrations.existsBySeason_IdAndEmailIgnoreCaseAndStatusIn(seasonId, email, BLOCKS_RESIGNUP)) {
            throw new BusinessRuleException("This email has already registered for the season");
        }
        Registration reg = new Registration();
        reg.setSociety(season.getSociety());
        reg.setSeason(season);
        reg.setFullName(r.fullName().trim());
        reg.setEmail(email);
        reg.setPhone(Text.clean(r.phone()));
        reg.setDateOfBirth(r.dateOfBirth());
        reg.setFlatNumber(Text.clean(r.flatNumber()));
        reg.setRole(r.role());
        reg.setBattingStyle(r.battingStyle());
        reg.setBowlingStyle(r.bowlingStyle());
        reg.setNotes(Text.clean(r.notes()));
        reg.setStatus(RegistrationStatus.PENDING);
        reg = registrations.save(reg);

        notifications.notifyAdmins(societyId, null, "New registration",
                reg.getFullName() + " registered for " + season.getName(), "/society-admin#registrations");
        return reg;
    }

    // ------------------------------------------------------------------ admin reads

    @Transactional(readOnly = true)
    public List<Registration> list(User actor, Long societyId, RegistrationStatus statusOrNull) {
        Tenancy.requireAdminOf(actor, societyId);
        return statusOrNull == null
                ? registrations.findBySociety_IdOrderByCreatedAtDesc(societyId)
                : registrations.findBySociety_IdAndStatusOrderByCreatedAtDesc(societyId, statusOrNull);
    }

    // ------------------------------------------------------------------ approve / reject

    /** Single approve: one approval of type REGISTRATION. */
    @Transactional
    public AdminApproval requestApproval(User actor, Long societyId, Long registrationId) {
        Tenancy.requireAdminOf(actor, societyId);
        Registration r = registrations.findByIdAndSociety_Id(registrationId, societyId)
                .orElseThrow(() -> new NotFoundException("Registration not found"));
        requirePending(r);
        quota.assertCanAddPlayers(societyId, 1);
        AdminApproval a = approvals.raise(actor, societyId, ApprovalEntityType.REGISTRATION, r.getId(), null,
                "Approve registration of " + r.getFullName(), null);
        hold(r, a);
        return a;
    }

    /**
     * Backing call for the registrations table. APPROVE raises ONE REGISTRATION_BATCH approval
     * wrapping all eligible ids, so admins vote once, not once per row. Ids from another society
     * are ignored by the society-scoped query, and non-PENDING rows are skipped.
     * REJECT is applied directly (the approval gate only protects approving).
     *
     * @return the batch approval for APPROVE, empty for REJECT
     */
    @Transactional
    public Optional<AdminApproval> bulkAction(User actor, Long societyId, Collection<Long> ids, String action) {
        Tenancy.requireAdminOf(actor, societyId);
        List<Registration> eligible = registrations.findByIdInAndSociety_Id(ids, societyId).stream()
                .filter(r -> r.getStatus() == RegistrationStatus.PENDING).toList();
        if (eligible.isEmpty()) {
            throw new BusinessRuleException("None of the selected registrations are pending");
        }
        if ("APPROVE".equalsIgnoreCase(action)) {
            quota.assertCanAddPlayers(societyId, eligible.size());
            List<Long> idList = eligible.stream().map(Registration::getId).toList();
            AdminApproval a = approvals.raise(actor, societyId, ApprovalEntityType.REGISTRATION_BATCH, null, idList,
                    "Approve " + eligible.size() + " registrations", null);
            eligible.forEach(r -> hold(r, a));
            return Optional.of(a);
        }
        if ("REJECT".equalsIgnoreCase(action)) {
            eligible.forEach(r -> reject(actor, r));
            return Optional.empty();
        }
        throw new BusinessRuleException("Unknown bulk action: " + action);
    }

    @Transactional
    public void reject(User actor, Long societyId, Long registrationId) {
        Tenancy.requireAdminOf(actor, societyId);
        Registration r = registrations.findByIdAndSociety_Id(registrationId, societyId)
                .orElseThrow(() -> new NotFoundException("Registration not found"));
        requirePending(r);
        reject(actor, r);
    }

    private void reject(User actor, Registration r) {
        r.setStatus(RegistrationStatus.REJECTED);
        registrations.save(r);
        audit.record(actor, r.getSocietyId(), AuditActions.REGISTRATION_REJECTED, "REGISTRATION", r.getId(),
                Map.of("status", "PENDING"), Map.of("status", "REJECTED"));
    }

    private void hold(Registration r, AdminApproval a) {
        r.setStatus(RegistrationStatus.AWAITING_APPROVAL);
        r.setApproval(a);
        registrations.save(r);
    }

    private static void requirePending(Registration r) {
        if (r.getStatus() != RegistrationStatus.PENDING) {
            throw new BusinessRuleException("Only pending registrations can be sent for approval");
        }
    }

    // ------------------------------------------------------------------ approval outcome

    @EventListener
    @Transactional
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        AdminApproval a = event.approval();
        if (!ApprovalEntityType.REGISTRATION.equals(a.getEntityType())
                && !ApprovalEntityType.REGISTRATION_BATCH.equals(a.getEntityType())) {
            return;
        }
        // Both single and batch approvals are found through the registration's approval link.
        List<Registration> held = registrations.findByApproval_Id(a.getId());
        switch (a.getStatus()) {
            case APPROVED -> held.forEach(r -> convert(r, event.decidedBy()));
            case REJECTED, WITHDRAWN -> held.forEach(r -> {
                // Back to PENDING (not REJECTED): one vote against a 50-row batch must not
                // reject 50 people. Admins can still reject individuals explicitly.
                if (r.getStatus() == RegistrationStatus.AWAITING_APPROVAL) {
                    r.setStatus(RegistrationStatus.PENDING);
                    r.setApproval(null);
                    registrations.save(r);
                }
            });
            default -> { }
        }
    }

    /** Auto-creates the Player and the AuctionLot, and links an existing login with the same email. */
    private void convert(Registration r, User decidedBy) {
        if (r.getPlayer() != null || r.getStatus() == RegistrationStatus.APPROVED) {
            return;
        }
        Long societyId = r.getSocietyId();
        // The players table has a unique (society, email) index. Reusing an existing player
        // with this email is correct: it is the same person registering for another season.
        Player player = players.findBySociety_IdAndEmailIgnoreCase(societyId, r.getEmail()).orElse(null);
        if (player == null) {
            quota.assertCanAddPlayers(societyId, 1);
            player = new Player();
            player.setSociety(r.getSociety());
            player.setName(r.getFullName());
            player.setEmail(r.getEmail());
            player.setPhone(r.getPhone());
            player.setDateOfBirth(r.getDateOfBirth());
            player.setFlatNumber(r.getFlatNumber());
            player.setRole(r.getRole());
            player.setBattingStyle(r.getBattingStyle());
            player.setBowlingStyle(r.getBowlingStyle());
            player.setBasePrice(defaultBasePrice);
            player = players.save(player);
        } else if (!Boolean.TRUE.equals(player.getActive())) {
            player.setActive(true);
        }

        if (!lots.existsBySeason_IdAndPlayer_Id(r.getSeason().getId(), player.getId())) {
            AuctionLot lot = new AuctionLot();
            lot.setSociety(r.getSociety());
            lot.setSeason(r.getSeason());
            lot.setPlayer(player);
            lot.setBasePrice(player.getBasePrice());
            lots.save(lot);
        }

        // Dual role (§6): an admin or player login registering for themselves gets linked.
        final Player linked = player;
        users.findByEmailIgnoreCase(r.getEmail())
                .filter(u -> societyId.equals(u.getSocietyId()) && u.getPlayer() == null)
                .ifPresent(u -> {
                    u.setPlayer(linked);
                    users.save(u);
                });

        r.setPlayer(player);
        r.setStatus(RegistrationStatus.APPROVED);
        registrations.save(r);
        audit.record(decidedBy, societyId, AuditActions.REGISTRATION_APPROVED, "REGISTRATION", r.getId(),
                Map.of("status", "AWAITING_APPROVAL"), Map.of("status", "APPROVED", "playerId", player.getId()));
    }
}