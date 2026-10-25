package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Append-only history of online bids; AuctionLot.currentBid holds only the latest. */
@Entity
@Table(name = "auction_bids")
@Getter
@Setter
@NoArgsConstructor
public class AuctionBid extends BaseEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id", nullable = false)
    private AuctionLot lot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(nullable = false)
    private Double amount;

    @Column(name = "bid_at", nullable = false)
    private LocalDateTime bidAt;

    @PrePersist
    protected void stampBid() {
        if (bidAt == null) {
            bidAt = LocalDateTime.now();
        }
    }

    @Override
    public Long getSocietyId() {
        return lot == null ? null : lot.getSocietyId();
    }
}