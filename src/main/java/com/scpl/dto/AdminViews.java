package com.scpl.dto;

import com.scpl.model.ApprovalStatus;
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
import com.scpl.model.VendorCategory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read models for the society admin pages. Player rows carry PII on purpose (the viewer is an admin of the
 * same society, §14); these records are built only by AdminConsoleService after Tenancy.requireAdminOf.
 */
public final class AdminViews {

    private AdminViews() {
    }

    public record Kpis(long teams, long players, long matches, long pendingApprovals, long awaitingMyVote,
                       String planLabel, String subscriptionStatus, Long trialDaysLeft, String nextFixture,
                       LocalDateTime nextFixtureDate, boolean readOnly) {
    }

    public record SeasonRow(Long id, String name, Integer year, SeasonStatus status, String statusLabel,
                            LocalDate startDate, LocalDate endDate, boolean registrationOpen, int oversPerMatch,
                            double teamPurse, double plannedBudget, String description) {
    }

    public record TeamRow(Long id, Long seasonId, String seasonName, String name, String shortName, String color,
                          String logoUrl, Long captainId, String captainName, double purseRemaining) {
    }

    public record PlayerRow(Long id, String name, String email, String phone, LocalDate dateOfBirth, String address,
                            String flatNumber, PlayerRole role, BattingStyle battingStyle, BowlingStyle bowlingStyle,
                            Integer jerseyNumber, double basePrice, String photoUrl, Long teamId, String teamName,
                            int matchesPlayed, int runs, int wickets, boolean active) {
    }

    /** actionable = still PENDING, so the table shows a checkbox and the approve/reject buttons. */
    public record RegistrationRow(Long id, String seasonName, String fullName, String email, String phone,
                                  String roleLabel, RegistrationStatus status, String statusLabel, boolean actionable,
                                  Long approvalId, LocalDateTime createdAt) {
    }

    public record LotRow(Long id, Long playerId, String playerName, double basePrice, double currentBid,
                         String currentBidTeam, Double soldPrice, String soldTeam, LotStatus status,
                         String statusLabel, AuctionMode mode, String modeLabel, boolean closed) {
    }

    public record MatchRow(Long id, Long seasonId, String seasonName, Long teamAId, Long teamBId, String teamA,
                           String teamB, LocalDateTime date, String venue, MatchStage stage, String stageLabel,
                           MatchStatus status, String statusLabel, Integer teamARuns, Integer teamAWickets,
                           Double teamAOvers, Integer teamBRuns, Integer teamBWickets, Double teamBOvers,
                           String winner, String resultSummary) {
    }

    public record SponsorRow(Long id, String name, SponsorTier tier, String tierLabel, String logoUrl,
                             String website, double amount, boolean active, Long seasonId, String seasonName) {
    }

    public record AwardRow(Long id, String title, AwardCategory category, String categoryLabel, String description,
                           String imageUrl, Long seasonId, String seasonName, Long playerId, String playerName,
                           Long teamId, String teamName) {
    }

    public record GalleryRow(Long id, String title, String imageUrl, String caption, Long seasonId, String seasonName) {
    }

    public record BillRow(Long id, String title, Long vendorId, String vendorName, String categoryLabel, Long seasonId,
                          String seasonName, double amount, LocalDate billDate, LocalDate paidDate, BillStatus status,
                          String statusLabel, String description, String attachmentUrl) {
    }

    public record ConsoleView(String societyName, String societySlug, String societyStatus, int adminCount,
                              Kpis kpis, Long selectedSeasonId, String selectedSeasonName, List<SeasonRow> seasons,
                              List<TeamRow> teams, List<TeamRow> selectedSeasonTeams, List<PlayerRow> players,
                              List<RegistrationRow> registrations, List<LotRow> lots, LotRow liveLot,
                              List<MatchRow> matches, List<StandingRow> standings, List<SponsorRow> sponsors,
                              List<AwardRow> awards, List<GalleryRow> gallery) {
    }

    // ------------------------------------------------------------------ approvals

    public record VoteRow(String voter, String voteLabel, LocalDateTime votedAt, String comment) {
    }

    /** canVote is false for the requester, for admins who already voted, and once the approval is decided. */
    public record ApprovalView(Long id, String type, String typeLabel, String notes, String requestedBy,
                               LocalDateTime requestedAt, ApprovalStatus status, String statusLabel, int required,
                               int eligible, long approveVotes, long rejectVotes, int itemCount, boolean mine,
                               boolean canVote, boolean canWithdraw, String myVote, LocalDateTime decidedAt,
                               String decisionNotes, List<VoteRow> votes) {
    }

    public record AuditRow(LocalDateTime timestamp, String user, String action, String entityType, Long entityId,
                           String detail) {
    }

    public record ApprovalsPageView(List<ApprovalView> pending, List<ApprovalView> history, List<AuditRow> audit,
                                    int auditPage, int auditPages) {
    }

    // ------------------------------------------------------------------ dropdown options

    public record FormOptions(SeasonStatus[] seasonStatuses, PlayerRole[] roles, BattingStyle[] battingStyles,
                              BowlingStyle[] bowlingStyles, MatchStage[] stages, AuctionMode[] auctionModes,
                              SponsorTier[] sponsorTiers, AwardCategory[] awardCategories,
                              VendorCategory[] vendorCategories, BillStatus[] billStatuses,
                              SubscriptionPlan[] paidPlans) {

        public static FormOptions all() {
            return new FormOptions(SeasonStatus.values(), PlayerRole.values(), BattingStyle.values(),
                    BowlingStyle.values(), MatchStage.values(), AuctionMode.values(), SponsorTier.values(),
                    AwardCategory.values(), VendorCategory.values(), BillStatus.values(),
                    new SubscriptionPlan[]{SubscriptionPlan.BASIC, SubscriptionPlan.STANDARD, SubscriptionPlan.PREMIUM});
        }
    }

    /** Current values for the console's Settings form, so saving it cannot blank fields it does not show. */
    public record SocietyDetails(String name, String slug, String description, String city, String address,
                                 String contactEmail, String contactPhone) {
    }
}