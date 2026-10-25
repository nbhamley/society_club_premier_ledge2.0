package com.scpl.service;

import com.scpl.dto.PlayerProfileView;
import com.scpl.dto.PlayerRequest;
import com.scpl.dto.PublicPlayerView;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AdminApproval;
import com.scpl.model.ApprovalEntityType;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.AuctionLot;
import com.scpl.model.LotStatus;
import com.scpl.model.Player;
import com.scpl.model.Team;
import com.scpl.model.User;
import com.scpl.repo.AuctionLotRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.util.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.scpl.util.Urls;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns every player read path, so the §14 privacy rule has one implementation:
 * PII is visible to the player themselves and to admins of the player's own society,
 * and to nobody else, including the platform admin.
 */
@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final AuctionLotRepository lots;
    private final SocietyRepository societies;
    private final QuotaService quota;
    private final AdminApprovalService approvals;
    private final AuditService audit;

    // ------------------------------------------------------------------ public / privacy-safe reads

    @Transactional(readOnly = true)
    public List<PublicPlayerView> publicRoster(Long societyId) {
        return players.findBySociety_IdAndActiveTrueOrderByNameAsc(societyId).stream()
                .map(p -> toPublic(p)).toList();
    }


    @Transactional(readOnly = true)
    public List<PublicPlayerView> teamRoster(Long societyId, Long teamId) {
        return players.findByTeam_IdOrderByNameAsc(teamId).stream()
                .filter(p -> societyId.equals(p.getSocietyId()) && Boolean.TRUE.equals(p.getActive()))
                .map(PlayerService::toPublic).toList();
    }

    /**
     * Profile for any viewer, anonymous included. The returned record has its PII fields
     * nulled on the server unless the viewer is entitled to them.
     */
    @Transactional(readOnly = true)
    public PlayerProfileView profile(User viewer, Long societyId, Long playerId) {
        Player p = players.findWithDetailsByIdAndSociety_Id(playerId, societyId)
                .orElseThrow(() -> new NotFoundException("Player not found"));
        boolean pii = canSeePii(viewer, p);
        if (!Boolean.TRUE.equals(p.getActive()) && !pii) {
            throw new NotFoundException("Player not found");
        }
        return toProfile(p, pii);
    }

    /** /me/player: the logged-in user's own linked player. */
    @Transactional(readOnly = true)
    public PlayerProfileView ownProfile(User user) {
        if (user.getPlayerId() == null || user.getSocietyId() == null) {
            throw new NotFoundException("You are not linked to a player profile");
        }
        return profile(user, user.getSocietyId(), user.getPlayerId());
    }

    /** Roster for platform admins and cross-society views: name, role and stats only, read via a closed projection. */
    @Transactional(readOnly = true)
    public List<PlayerRepository.PlayerRosterRow> statsOnlyRoster(Long societyId) {
        return players.findRosterBySociety_IdAndActiveTrueOrderByNameAsc(societyId);
    }

    static boolean canSeePii(User viewer, Player p) {
        if (viewer == null) {
            return false;
        }
        if (viewer.isSocietyAdmin() && Boolean.TRUE.equals(viewer.getEnabled())
                && Objects.equals(viewer.getSocietyId(), p.getSocietyId())) {
            return true;
        }
        return viewer.getPlayerId() != null && viewer.getPlayerId().equals(p.getId());
    }

    // ------------------------------------------------------------------ admin reads and writes

    /** Full entities with PII. Callers must be society admin endpoints; the actor check enforces it. */
    @Transactional(readOnly = true)
    public List<Player> listForAdmin(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        return players.findBySociety_IdOrderByNameAsc(societyId);
    }

    @Transactional(readOnly = true)
    public Player getForAdmin(User actor, Long societyId, Long playerId) {
        Tenancy.requireAdminOf(actor, societyId);
        return find(societyId, playerId);
    }

    @Transactional
    public Player create(User actor, Long societyId, PlayerRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertCanAddPlayers(societyId, 1);
        assertEmailFree(societyId, Text.clean(r.email()), null);
        Player p = new Player();
        p.setSociety(societies.getReferenceById(societyId));
        apply(p, r, societyId);
        p = players.save(p);
        audit.record(actor, societyId, AuditActions.PLAYER_CREATED, "PLAYER", p.getId(), null, Map.of("name", p.getName()));
        return p;
    }

    @Transactional
    public Player update(User actor, Long societyId, Long playerId, PlayerRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        Player p = find(societyId, playerId);
        assertEmailFree(societyId, Text.clean(r.email()), playerId);
        Map<String, Object> before = Map.of("name", p.getName());
        apply(p, r, societyId);
        players.save(p);
        audit.record(actor, societyId, AuditActions.PLAYER_UPDATED, "PLAYER", playerId, before, Map.of("name", p.getName()));
        return p;
    }

    /** Bulk action: assign (or with a null team, release) many players at once. Foreign ids are rejected. */
    @Transactional
    public int bulkAssignTeam(User actor, Long societyId, Collection<Long> playerIds, Long teamId) {
        Tenancy.requireAdminOf(actor, societyId);
        Team team = teamId == null ? null : teams.findByIdAndSociety_Id(teamId, societyId)
                .orElseThrow(() -> new NotFoundException("Team not found"));
        int changed = 0;
        for (Long id : playerIds) {
            Player p = find(societyId, id);
            p.setTeam(team);
            players.save(p);
            changed++;
        }
        return changed;
    }

    /** Dropping a player is destructive for fixtures and auctions, so it needs a 3-of-N approval. */
    @Transactional
    public AdminApproval requestDrop(User actor, Long societyId, Long playerId, String reason) {
        Tenancy.requireAdminOf(actor, societyId);
        Player p = find(societyId, playerId);
        String notes = "Drop player " + p.getName() + (Text.clean(reason) != null ? ": " + reason.trim() : "");
        return approvals.raise(actor, societyId, ApprovalEntityType.PLAYER_DROP, playerId, null, notes, null);
    }

    @EventListener
    @Transactional
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        AdminApproval a = event.approval();
        if (a.getStatus() != ApprovalStatus.APPROVED || !ApprovalEntityType.PLAYER_DROP.equals(a.getEntityType())) {
            return;
        }
        Long societyId = a.getSocietyId();
        Player p = find(societyId, a.getEntityId());

        // Soft drop: the row stays because sold lots, awards and stats reference it.
        p.setActive(false);
        p.setTeam(null);
        players.save(p);

        for (Team t : teams.findBySociety_IdOrderByNameAsc(societyId)) {
            if (t.getCaptain() != null && t.getCaptain().getId().equals(p.getId())) {
                t.setCaptain(null);
                teams.save(t);
            }
        }
        // Only unsold, unfinished lots go; SOLD and UNSOLD lots are history.
        for (AuctionLot lot : lots.findBySociety_Id(societyId)) {
            boolean open = lot.getStatus() == LotStatus.PENDING || lot.getStatus() == LotStatus.LIVE;
            if (open && lot.getPlayer().getId().equals(p.getId())) {
                lots.delete(lot);
            }
        }
        audit.record(event.decidedBy(), societyId, AuditActions.PLAYER_DROPPED, "PLAYER", p.getId(),
                Map.of("name", p.getName()), Map.of("active", false));
    }

    // ------------------------------------------------------------------ internals

    private Player find(Long societyId, Long playerId) {
        return players.findByIdAndSociety_Id(playerId, societyId)
                .orElseThrow(() -> new NotFoundException("Player not found"));
    }

    private void assertEmailFree(Long societyId, String email, Long selfId) {
        if (email == null) {
            return;
        }
        players.findBySociety_IdAndEmailIgnoreCase(societyId, email)
                .filter(other -> !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw new BusinessRuleException("Another player in this society already uses that email");
                });
    }

    private void apply(Player p, PlayerRequest r, Long societyId) {
        p.setName(r.name().trim());
        p.setEmail(Text.clean(r.email()));
        p.setPhone(Text.clean(r.phone()));
        p.setDateOfBirth(r.dateOfBirth());
        p.setAddress(Text.clean(r.address()));
        p.setFlatNumber(Text.clean(r.flatNumber()));
        p.setRole(r.role());
        p.setBattingStyle(r.battingStyle());
        p.setBowlingStyle(r.bowlingStyle());
        p.setJerseyNumber(r.jerseyNumber());
        p.setBasePrice(r.basePrice() == null ? 0.0 : Math.max(0.0, r.basePrice()));
        p.setPhotoUrl(Urls.requireWebUrl(r.photoUrl(), "Photo"));
        p.setTeam(r.teamId() == null ? null : teams.findByIdAndSociety_Id(r.teamId(), societyId)
                .orElseThrow(() -> new NotFoundException("Team not found")));
    }

    private static PublicPlayerView toPublic(Player p) {
        return new PublicPlayerView(p.getId(), p.getName(), p.getRole(),
                p.getTeam() == null ? null : p.getTeam().getName(), p.getPhotoUrl(),
                p.getMatchesPlayed(), p.getRuns(), p.getWickets());
    }

    private static PlayerProfileView toProfile(Player p, boolean pii) {
        Team t = p.getTeam();
        return new PlayerProfileView(p.getId(), p.getName(), p.getRole(), p.getBattingStyle(), p.getBowlingStyle(),
                p.getJerseyNumber(), p.getPhotoUrl(), t == null ? null : t.getId(), t == null ? null : t.getName(),
                p.getMatchesPlayed(), p.getRuns(), p.getWickets(), pii,
                pii ? p.getEmail() : null, pii ? p.getPhone() : null, pii ? p.getDateOfBirth() : null,
                pii ? p.getAddress() : null, pii ? p.getFlatNumber() : null);
    }
}