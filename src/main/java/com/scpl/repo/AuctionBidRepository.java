package com.scpl.repo;

import com.scpl.model.AuctionBid;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuctionBidRepository extends JpaRepository<AuctionBid, Long> {

    @EntityGraph(attributePaths = "team")
    List<AuctionBid> findByLot_IdOrderByAmountDesc(Long lotId);

    @EntityGraph(attributePaths = "team")
    Optional<AuctionBid> findFirstByLot_IdOrderByAmountDesc(Long lotId);

    long countByLot_Id(Long lotId);

    /** Lot reset clears the bid history in one statement instead of loading each row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AuctionBid b where b.lot.id = :lotId")
    int deleteAllForLot(@Param("lotId") Long lotId);
}