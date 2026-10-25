package com.scpl.dto;

import com.scpl.dto.PublicViews.MatchView;

import java.time.LocalDateTime;
import java.util.List;

public final class DashboardViews {

    private DashboardViews() {
    }

    /**
     * Role-routed dashboard (§7). admin selects the KPI tiles; ownProfile (with the player's own PII, which
     * they are entitled to) is set for anyone linked to a player, including a dual-role admin.
     */
    public record DashboardView(boolean admin, String societyName, String societySlug, String societyStatus,
                                String seasonName, long teams, long players, long matches, long pendingApprovals,
                                long awaitingMyVote, String nextFixture, LocalDateTime nextFixtureDate,
                                String planLabel, String subscriptionStatus, Long trialDaysLeft, boolean readOnly,
                                PlayerProfileView ownProfile) {
    }

    /** /me/team. The roster is PII-free by construction (PublicPlayerView has no PII fields). */
    public record TeamHomeView(Long teamId, String teamName, String shortName, String color, String logoUrl,
                               Long seasonId, String seasonName, String captainName, boolean viewerIsCaptain,
                               List<PublicPlayerView> roster, List<StandingRow> standings, List<MatchView> matches) {
    }
}