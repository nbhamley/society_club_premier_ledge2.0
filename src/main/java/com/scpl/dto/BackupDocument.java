package com.scpl.dto;

import com.scpl.model.AuctionMode;
import com.scpl.model.AwardCategory;
import com.scpl.model.BattingStyle;
import com.scpl.model.BillStatus;
import com.scpl.model.BowlingStyle;
import com.scpl.model.LotStatus;
import com.scpl.model.MatchStage;
import com.scpl.model.MatchStatus;
import com.scpl.model.PlayerRole;
import com.scpl.model.RegistrationStatus;
import com.scpl.model.SeasonStatus;
import com.scpl.model.SponsorTier;
import com.scpl.model.SubscriptionPlan;
import com.scpl.model.SubscriptionStatus;
import com.scpl.model.VendorCategory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The single JSON document inside a society backup (§10). Ids are used only to wire this society's
 * rows to each other and are remapped on restore. It deliberately omits: login accounts and password
 * hashes, payment-gateway customer ids, approval history, and audit logs (a restore starts a fresh
 * history under the new society).
 */
public record BackupDocument(int formatVersion, LocalDateTime exportedAtUtc, SocietyData society,
                             List<SeasonData> seasons, List<TeamData> teams, List<PlayerData> players,
                             List<MatchData> matches, List<RegistrationData> registrations, List<LotData> lots,
                             List<BidData> bids, List<SponsorData> sponsors, List<AwardData> awards,
                             List<GalleryData> gallery, List<VendorData> vendors, List<BillData> bills,
                             List<ContactData> contacts, List<SubscriptionData> subscriptions) {

    public static final int CURRENT_VERSION = 1;

    public record SocietyData(String name, String slug, String description, String city, String address,
                              String logoUrl, String contactEmail, String contactPhone, LocalDateTime createdAt) {
    }

    public record SeasonData(Long id, String name, Integer year, SeasonStatus status, LocalDate startDate,
                             LocalDate endDate, boolean registrationOpen, int oversPerMatch, double teamPurse,
                             double plannedBudget, String description, LocalDateTime createdAt) {
    }

    public record TeamData(Long id, Long seasonId, String name, String shortName, String color, String logoUrl,
                           Long captainPlayerId, double purseRemaining, LocalDateTime createdAt) {
    }

    public record PlayerData(Long id, Long teamId, String name, String email, String phone, LocalDate dateOfBirth,
                             String address, String flatNumber, PlayerRole role, BattingStyle battingStyle,
                             BowlingStyle bowlingStyle, Integer jerseyNumber, double basePrice, String photoUrl,
                             int matchesPlayed, int runs, int wickets, boolean active, LocalDateTime createdAt) {
    }

    public record MatchData(Long id, Long seasonId, Long teamAId, Long teamBId, LocalDateTime matchDate,
                            String venue, MatchStage stage, MatchStatus status, Integer teamARuns,
                            Integer teamAWickets, Double teamAOvers, Integer teamBRuns, Integer teamBWickets,
                            Double teamBOvers, Long winnerTeamId, String resultSummary, LocalDateTime createdAt) {
    }

    public record RegistrationData(Long id, Long seasonId, String fullName, String email, String phone,
                                   LocalDate dateOfBirth, String flatNumber, PlayerRole role,
                                   BattingStyle battingStyle, BowlingStyle bowlingStyle, String notes,
                                   RegistrationStatus status, Long playerId, LocalDateTime createdAt) {
    }

    public record LotData(Long id, Long seasonId, Long playerId, double basePrice, double currentBid,
                          Long currentBidTeamId, Double soldPrice, Long soldTeamId, LotStatus status,
                          AuctionMode mode, LocalDateTime createdAt) {
    }

    public record BidData(Long lotId, Long teamId, double amount, LocalDateTime bidAt) {
    }

    public record SponsorData(Long seasonId, String name, SponsorTier tier, String logoUrl, String website,
                              double amount, boolean active, LocalDateTime createdAt) {
    }

    public record AwardData(Long seasonId, Long playerId, Long teamId, String title, AwardCategory category,
                            String description, String imageUrl, LocalDateTime createdAt) {
    }

    public record GalleryData(Long seasonId, String title, String imageUrl, String caption, LocalDateTime createdAt) {
    }

    public record VendorData(Long id, String name, VendorCategory category, String contactPerson, String phone,
                             String whatsappNumber, String email, String address, String city, String website,
                             Integer rating, String notes, boolean active, LocalDateTime createdAt) {
    }

    public record BillData(Long vendorId, Long seasonId, String title, double amount, LocalDate billDate,
                           LocalDate paidDate, BillStatus status, String description, String attachmentUrl,
                           LocalDateTime createdAt) {
    }

    public record ContactData(String name, String roleTitle, String phone, String whatsappNumber, String email,
                              LocalDateTime createdAt) {
    }

    /** Exported for the record, ignored on restore: the new society starts its own trial. */
    public record SubscriptionData(SubscriptionPlan plan, SubscriptionStatus status, LocalDateTime startedAt,
                                   LocalDateTime expiresAt, double monthlyCost, String currency, boolean autoRenew,
                                   LocalDateTime lastPaymentAt, LocalDateTime nextBillingAt,
                                   SubscriptionPlan nextPlan) {
    }
}