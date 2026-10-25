package com.scpl.service;

import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AuctionBid;
import com.scpl.model.AuctionLot;
import com.scpl.model.AuctionMode;
import com.scpl.model.LotStatus;
import com.scpl.model.Player;
import com.scpl.model.Season;
import com.scpl.model.Team;
import com.scpl.model.User;
import com.scpl.repo.AuctionBidRepository;
import com.scpl.repo.AuctionLotRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Online bidding plus admin-run offline sales. Lots are normally created when a registration
 * is approved; addLot covers players an admin created by hand.
 */
@Service
@RequiredArgsConstructor
public class AuctionService {

    private final AuctionLotRepository lots;
    private final AuctionBidRepository bids;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final SeasonRepository seasons;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;

    @Transactional(readOnly = true)
    public List<AuctionLot> lotsForSeason(Long societyId, Long seasonId) {
        return lots.findBySeason_IdOrderByIdAsc(seasonId).stream()
                .filter(l -> societyId.equals(l.getSocietyId())).toList();
    }

    @Transactional(readOnly = true)
    public AuctionLot liveLot(Long societyId, Long seasonId) {
        return lots.findFirstBySeason_IdAndStatus(seasonId, LotStatus.LIVE)
                .filter(l -> societyId.equals(l.getSocietyId())).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AuctionBid> bidHistory(Long societyId, Long lotId) {
        load(societyId, lotId);
        return bids.findByLot_IdOrderByAmountDesc(lotId);
    }

    @Transactional
    public AuctionLot addLot(User actor, Long societyId, Long seasonId, Long playerId) {
        Tenancy.requireAdminOf(actor, societyId);
        Season season = seasons.findByIdAndSociety_Id(seasonId, societyId)
                .orElseThrow(() -> new NotFoundException("Season not found"));
        Player p = players.findByIdAndSociety_Id(playerId, societyId)
                .orElseThrow(() -> new NotFoundException("Player not found"));
        if (!Boolean.TRUE.equals(p.getActive())) {
            throw new BusinessRuleException("Dropped players cannot be auctioned");
        }
        if (lots.existsBySeason_IdAndPlayer_Id(seasonId, playerId)) {
            throw new BusinessRuleException("This player already has a lot in the season");
        }
        AuctionLot lot = new AuctionLot();
        lot.setSociety(season.getSociety());
        lot.setSeason(season);
        lot.setPlayer(p);
        lot.setBasePrice(p.getBasePrice());
        return lots.save(lot);
    }

    @Transactional
    public AuctionLot start(User actor, Long societyId, Long lotId) {
        Tenancy.requireAdminOf(actor, societyId);
        AuctionLot lot = load(societyId, lotId);
        if (lot.isClosed()) {
            throw new BusinessRuleException("This lot is already closed. Reset it to run it again.");
        }
        if (lot.getStatus() == LotStatus.LIVE) {
            return lot;
        }
        if (lots.countBySeason_IdAndStatus(lot.getSeason().getId(), LotStatus.LIVE) > 0) {
            throw new BusinessRuleException("Another lot is live. Sell or mark it unsold first.");
        }
        lot.setStatus(LotStatus.LIVE);
        return lots.save(lot);
    }

    @Transactional
    public AuctionLot setMode(User actor, Long societyId, Long lotId, AuctionMode mode) {
        Tenancy.requireAdminOf(actor, societyId);
        AuctionLot lot = load(societyId, lotId);
        if (lot.isClosed()) {
            throw new BusinessRuleException("A closed lot cannot change mode");
        }
        lot.setMode(mode);
        return lots.save(lot);
    }

    // ------------------------------------------------------------------ bidding

    /**
     * Online bid by a team captain (or an admin acting for a team). The accept/reject decision
     * is a single conditional UPDATE inside the repository, so two simultaneous bids cannot
     * both win; the purse and base-price rules are checked before it.
     */
    @Transactional
    public AuctionBid placeBid(User bidder, Long lotId, Long teamId, double amount) {
        Long societyId = bidder.getSocietyId();
        if (societyId == null) {
            throw new BusinessRuleException("Only society members can bid");
        }
        AuctionLot lot = load(societyId, lotId);
        if (!lot.isAcceptingBids()) {
            throw new BusinessRuleException("This lot is not open for online bids");
        }
        Team team = teams.findByIdAndSociety_Id(teamId, societyId)
                .orElseThrow(() -> new NotFoundException("Team not found"));
        if (!team.getSeasonId().equals(lot.getSeason().getId())) {
            throw new BusinessRuleException("That team is not in this season");
        }
        boolean isCaptain = bidder.getPlayerId() != null && team.getCaptain() != null
                && bidder.getPlayerId().equals(team.getCaptain().getId());
        if (!isCaptain && !(bidder.isSocietyAdmin() && Boolean.TRUE.equals(bidder.getEnabled()))) {
            throw new BusinessRuleException("Only the team captain or an admin can bid for this team");
        }
        if (amount < lot.getBasePrice()) {
            throw new BusinessRuleException("The bid is below the base price");
        }
        if (amount > team.getPurseRemaining()) {
            throw new BusinessRuleException("The bid exceeds the team's remaining purse");
        }
        if (lots.raiseBid(lotId, amount, team, LotStatus.LIVE) == 0) {
            throw new BusinessRuleException("Bid rejected: it must beat the current bid while the lot is live");
        }
        AuctionBid bid = new AuctionBid();
        bid.setLot(lot);
        bid.setTeam(team);
        bid.setAmount(amount);
        return bids.save(bid);
    }

    /** Admin correction: sets the standing bid directly, even downwards (for a mis-typed bid). */
    @Transactional
    public AuctionLot overrideBid(User actor, Long societyId, Long lotId, Long teamId, double amount) {
        Tenancy.requireAdminOf(actor, societyId);
        AuctionLot lot = load(societyId, lotId);
        if (lot.isClosed()) {
            throw new BusinessRuleException("This lot is closed");
        }
        Team team = requireTeamInSeason(societyId, teamId, lot);
        if (amount < lot.getBasePrice() || amount > team.getPurseRemaining()) {
            throw new BusinessRuleException("The amount must be at least the base price and within the team's purse");
        }
        Map<String, Object> before = Map.of("bid", lot.getCurrentBid());
        lot.setCurrentBid(amount);
        lot.setCurrentBidTeam(team);
        lots.save(lot);

        AuctionBid bid = new AuctionBid();
        bid.setLot(lot);
        bid.setTeam(team);
        bid.setAmount(amount);
        bids.save(bid);
        audit.record(actor, societyId, AuditActions.AUCTION_BID_OVERRIDE, "AUCTION_LOT", lotId, before,
                Map.of("bid", amount, "team", team.getName()));
        return lot;
    }

    // ------------------------------------------------------------------ closing a lot

    /**
     * Records a sale. For an online lot, team and price default to the standing bid; for an
     * offline (hall) sale the admin supplies both.
     */
    @Transactional
    public AuctionLot markSold(User actor, Long societyId, Long lotId, Long teamIdOrNull, Double priceOrNull) {
        Tenancy.requireAdminOf(actor, societyId);
        AuctionLot lot = load(societyId, lotId);
        if (lot.isClosed()) {
            throw new BusinessRuleException("This lot is already closed. Reset it first to change the result.");
        }
        Team team = teamIdOrNull != null ? requireTeamInSeason(societyId, teamIdOrNull, lot) : lot.getCurrentBidTeam();
        double price = priceOrNull != null ? priceOrNull : lot.getCurrentBid();
        if (team == null || price <= 0) {
            throw new BusinessRuleException("Choose a team and a price, or wait for an online bid");
        }
        if (price < lot.getBasePrice()) {
            throw new BusinessRuleException("The price is below the base price");
        }
        if (price > team.getPurseRemaining()) {
            throw new BusinessRuleException("The price exceeds the team's remaining purse");
        }
        assertNoConflict(actor, lot, team);

        lot.setStatus(LotStatus.SOLD);
        lot.setSoldTeam(team);
        lot.setSoldPrice(price);
        lot.setCurrentBid(price);
        lot.setCurrentBidTeam(team);
        lots.save(lot);

        Player player = lot.getPlayer();
        player.setTeam(team);
        players.save(player);
        team.setPurseRemaining(team.getPurseRemaining() - price);
        teams.save(team);

        audit.record(actor, societyId, AuditActions.AUCTION_SOLD, "AUCTION_LOT", lotId, null,
                Map.of("player", player.getName(), "team", team.getName(), "price", price));

        // §6: record-keeping notice when the winning team is led by someone who is not an admin.
        boolean ledByNonAdmin = team.getCaptain() == null || users.findByPlayer_Id(team.getCaptain().getId())
                .map(u -> !u.isSocietyAdmin()).orElse(true);
        if (ledByNonAdmin) {
            notifications.notifyAdmins(societyId, actor.getId(), "Lot sold",
                    String.format(Locale.ROOT, "%s sold to %s for %.0f", player.getName(), team.getName(), price),
                    "/society-admin#auction");
        }
        return lot;
    }

    @Transactional
    public AuctionLot markUnsold(User actor, Long societyId, Long lotId) {
        Tenancy.requireAdminOf(actor, societyId);
        AuctionLot lot = load(societyId, lotId);
        if (lot.isClosed()) {
            throw new BusinessRuleException("This lot is already closed");
        }
        lot.setStatus(LotStatus.UNSOLD);
        lots.save(lot);
        audit.record(actor, societyId, AuditActions.AUCTION_UNSOLD, "AUCTION_LOT", lotId, null,
                Map.of("player", lot.getPlayer().getName()));
        return lot;
    }

    /** Returns the lot to PENDING, refunds the purse if it had been sold, and clears the bid history. */
    @Transactional
    public AuctionLot reset(User actor, Long societyId, Long lotId) {
        Tenancy.requireAdminOf(actor, societyId);
        AuctionLot lot = load(societyId, lotId);
        Long refundTeamId = lot.getStatus() == LotStatus.SOLD && lot.getSoldTeam() != null
                ? lot.getSoldTeam().getId() : null;
        double refund = lot.getSoldPrice() == null ? 0.0 : lot.getSoldPrice();
        Long playerId = lot.getPlayer().getId();
        String previous = lot.getStatus().name();

        // The bulk delete clears the persistence context, so the entities are reloaded after it.
        bids.deleteAllForLot(lotId);
        AuctionLot fresh = load(societyId, lotId);
        if (refundTeamId != null) {
            Team team = teams.findById(refundTeamId).orElseThrow();
            team.setPurseRemaining(team.getPurseRemaining() + refund);
            teams.save(team);
            Player player = players.findById(playerId).orElseThrow();
            player.setTeam(null);
            players.save(player);
        }
        fresh.setStatus(LotStatus.PENDING);
        fresh.setCurrentBid(0.0);
        fresh.setCurrentBidTeam(null);
        fresh.setSoldPrice(null);
        fresh.setSoldTeam(null);
        lots.save(fresh);
        audit.record(actor, societyId, AuditActions.AUCTION_RESET, "AUCTION_LOT", lotId,
                Map.of("status", previous), Map.of("status", "PENDING"));
        return fresh;
    }

    // ------------------------------------------------------------------ internals

    private AuctionLot load(Long societyId, Long lotId) {
        return lots.findByIdAndSociety_Id(lotId, societyId)
                .orElseThrow(() -> new NotFoundException("Auction lot not found"));
    }

    private Team requireTeamInSeason(Long societyId, Long teamId, AuctionLot lot) {
        Team team = teams.findByIdAndSociety_Id(teamId, societyId)
                .orElseThrow(() -> new NotFoundException("Team not found"));
        if (!team.getSeasonId().equals(lot.getSeason().getId())) {
            throw new BusinessRuleException("That team is not in this season");
        }
        return team;
    }

    /**
     * An admin who is also a player must not record the sale of their own lot, or a sale to the
     * team they captain (§6: admin-as-player cannot approve their own auction wins). The
     * other admins record it instead.
     */
    private void assertNoConflict(User actor, AuctionLot lot, Team team) {
        Long own = actor.getPlayerId();
        if (own == null) {
            return;
        }
        boolean ownLot = own.equals(lot.getPlayer().getId());
        boolean ownTeam = team.getCaptain() != null && own.equals(team.getCaptain().getId());
        if (ownLot || ownTeam) {
            throw new BusinessRuleException("You are involved in this sale as a player, so another admin must record it");
        }
    }
}