package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "auction_lots")
@Getter
@Setter
@NoArgsConstructor
public class AuctionLot extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "base_price", nullable = false)
    private Double basePrice = 0.0;

    @Column(name = "current_bid", nullable = false)
    private Double currentBid = 0.0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_bid_team_id")
    private Team currentBidTeam;

    @Column(name = "sold_price")
    private Double soldPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sold_team_id")
    private Team soldTeam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LotStatus status = LotStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionMode mode = AuctionMode.ONLINE;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    /** Bids are only accepted while the lot is LIVE and in online mode. */
    public boolean isAcceptingBids() {
        return status == LotStatus.LIVE && mode == AuctionMode.ONLINE;
    }

    public boolean isClosed() {
        return status == LotStatus.SOLD || status == LotStatus.UNSOLD;
    }
}