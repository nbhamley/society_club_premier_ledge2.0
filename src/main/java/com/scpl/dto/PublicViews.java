package com.scpl.dto;

import com.scpl.model.SubscriptionPlan;
import com.scpl.util.PlanCatalog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Models for every public page. They are built from entities inside a transaction, so templates
 * never touch a lazy association. Private fields (planned budget, team purses, sponsor amounts,
 * player PII) have no slot here, so a template cannot leak them.
 */
public final class PublicViews {

    private PublicViews() {
    }

    public record SocietyCard(String name, String slug, String city, String logoUrl, String description) {
    }

    public record EventView(Long id, String title, String description, LocalDateTime eventDate, String typeLabel,
                            String thumbnailUrl, String externalLink, String societyName, String societySlug) {
    }

    public record SponsorView(String name, String tierLabel, String logoUrl, String website, String societyName) {
    }

    public record WinnerView(String title, String categoryLabel, String winnerName, String imageUrl,
                             String societyName, String societySlug, String seasonName) {
    }

    public record PlanView(SubscriptionPlan plan, String label, double monthlyCost, List<String> features) {
    }

    public record TeamView(Long id, String name, String shortName, String color, String logoUrl, String captainName) {
    }

    public record MatchView(Long id, String teamA, String teamB, String teamAColor, String teamBColor,
                            LocalDateTime date, String venue, String stageLabel, String statusLabel,
                            Integer teamARuns, Integer teamAWickets, Double teamAOvers,
                            Integer teamBRuns, Integer teamBWickets, Double teamBOvers,
                            String winnerName, String resultSummary) {
    }

    public record SeasonView(Long id, String name, Integer year, String statusLabel, LocalDate startDate,
                             LocalDate endDate, boolean registrationOpen, int oversPerMatch, String description) {
    }

    public record GalleryView(Long id, String title, String imageUrl, String caption) {
    }

    public record PlatformHomeView(List<EventView> events, List<SponsorView> sponsors, List<WinnerView> winners,
                                   List<EventView> thumbnails, List<SocietyCard> societies, List<PlanView> plans) {
    }

    public record SocietyHomeView(Long id, String name, String slug, String description, String city,
                                  String logoUrl, SeasonView season, List<StandingRow> standings,
                                  List<MatchView> upcoming, List<MatchView> recentResults, List<TeamView> teams,
                                  List<GalleryView> gallery, List<WinnerView> awards, List<SponsorView> sponsors,
                                  List<EventView> events) {
    }

    public record SeasonDetailView(String societyName, String societySlug, SeasonView season,
                                   List<StandingRow> standings, List<TeamView> teams, List<MatchView> matches,
                                   List<WinnerView> awards) {
    }

    public record MatchDetailView(String societyName, String societySlug, String seasonName, MatchView match) {
    }

    /** Public registration page: only seasons that are accepting sign-ups. */
    public record RegisterView(String societyName, String societySlug, List<SeasonView> seasons) {
    }

    /** Pricing matrix for the platform home, /subscribe and the billing page. Built from PlanCatalog only. */
    public static List<PlanView> plans() {
        return Arrays.stream(SubscriptionPlan.values()).map(plan -> {
            PlanCatalog.Limits l = PlanCatalog.of(plan);
            List<String> f = new ArrayList<>();
            if (plan == SubscriptionPlan.FREE_TRIAL) {
                f.add("Free for the trial period");
            }
            f.add(l.maxTeams() == PlanCatalog.UNLIMITED ? "Unlimited teams" : "Up to " + l.maxTeams() + " teams per season");
            f.add(l.maxPlayers() == PlanCatalog.UNLIMITED ? "Unlimited players" : "Up to " + l.maxPlayers() + " players");
            f.add(l.maxConcurrentSeasons() == PlanCatalog.UNLIMITED ? "Unlimited seasons"
                    : l.maxConcurrentSeasons() + " concurrent season" + (l.maxConcurrentSeasons() > 1 ? "s" : ""));
            if (l.vendorModule()) {
                f.add("Vendor directory and bills");
            }
            if (l.aiImport()) {
                f.add("AI-assisted bulk import");
            }
            if (l.crossSociety()) {
                f.add("Cross-society tournaments");
            }
            return new PlanView(plan, plan.getLabel(), l.monthlyCost(), List.copyOf(f));
        }).toList();
    }
}