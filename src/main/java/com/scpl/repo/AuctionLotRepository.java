package com.scpl.repo;

import com.scpl.model.AuctionLot;
import com.scpl.model.LotStatus;
import com.scpl.model.Team;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuctionLotRepository extends SocietyScopedRepository<AuctionLot, Long> {

    @EntityGraph(attributePaths = {"player", "currentBidTeam", "soldTeam"})
    List<AuctionLot> findBySeason_IdOrderByIdAsc(Long seasonId);

    @EntityGraph(attributePaths = {"player", "currentBidTeam", "soldTeam"})
    List<AuctionLot> findBySeason_IdAndStatusOrderByIdAsc(Long seasonId, LotStatus status);

    @EntityGraph(attributePaths = {"player", "currentBidTeam", "soldTeam"})
    Optional<AuctionLot> findFirstBySeason_IdAndStatus(Long seasonId, LotStatus status);

    boolean existsBySeason_IdAndPlayer_Id(Long seasonId, Long playerId);

    long countBySeason_IdAndStatus(Long seasonId, LotStatus status);

    /**
     * Atomic compare-and-set for online bidding. The WHERE clause rejects a bid that is not
     * strictly higher or arrives after the lot closed, so two simultaneous bids cannot both
     * win. Chosen over a pessimistic lock because SQLite has no SELECT ... FOR UPDATE.
     * Returns 1 when the bid was accepted, 0 otherwise.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuctionLot l set l.currentBid = :amount, l.currentBidTeam = :team "
            + "where l.id = :id and l.status = :live and l.currentBid < :amount")
    int raiseBid(@Param("id") Long id,
                 @Param("amount") Double amount,
                 @Param("team") Team team,
                 @Param("live") LotStatus live);
}