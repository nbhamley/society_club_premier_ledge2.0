package com.scpl.service;

import com.scpl.dto.PlayerProfileView;
import com.scpl.dto.PublicPlayerView;
import com.scpl.dto.PublicViews;
import com.scpl.dto.PublicViews.EventView;
import com.scpl.dto.PublicViews.GalleryView;
import com.scpl.dto.PublicViews.MatchView;
import com.scpl.dto.PublicViews.SeasonCard;
import com.scpl.dto.PublicViews.SeasonView;
import com.scpl.dto.PublicViews.SocietyCard;
import com.scpl.dto.PublicViews.SponsorView;
import com.scpl.dto.PublicViews.TeamView;
import com.scpl.dto.PublicViews.WinnerView;
import com.scpl.dto.StandingRow;
import com.scpl.exception.NotFoundException;
import com.scpl.model.Award;
import com.scpl.model.Match;
import com.scpl.model.MatchStatus;
import com.scpl.model.PlatformEvent;
import com.scpl.model.Season;
import com.scpl.model.SeasonStatus;
import com.scpl.model.Society;
import com.scpl.model.SocietyStatus;
import com.scpl.model.Sponsor;
import com.scpl.model.Team;
import com.scpl.model.User;
import com.scpl.repo.AwardRepository;
import com.scpl.repo.GalleryImageRepository;
import com.scpl.repo.PlatformEventRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SponsorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Read models for every public page. Each method resolves the society through
 * SocietyService.getPublicBySlug, so DRAFT and SUSPENDED tenants are invisible, and builds
 * immutable views inside the transaction so templates never trigger a lazy load.
 */
@Service
@RequiredArgsConstructor
public class PublicSiteService {

    private static final int RECENT_RESULTS = 5;
    private static final int UPCOMING_FIXTURES = 5;
    private static final int UPCOMING_EVENTS = 6;
    private static final int UPCOMING_SEASONS = 6;
    private static final Set<SeasonStatus> OPEN_SEASONS = EnumSet.complementOf(EnumSet.of(SeasonStatus.COMPLETED));

    private final SocietyService societyService;
    private final SeasonService seasonService;
    private final TeamService teamService;
    private final MatchService matchService;
    private final PlayerService playerService;
    private final PlatformEventRepository events;
    private final SponsorRepository sponsors;
    private final AwardRepository awards;
    private final GalleryImageRepository gallery;
    private final SeasonRepository seasonRepository;

    // ------------------------------------------------------------------ platform home

    @Transactional(readOnly = true)
    public PublicViews.PlatformHomeView platformHome() {
        LocalDateTime now = LocalDateTime.now();
        // Platform-wide events plus those of ACTIVE societies; a society event links to its society's page.
        List<EventView> upcoming = events.findTop12ByPublishedTrueAndEventDateAfterOrderByEventDateAsc(now).stream()
                .filter(e -> e.getSociety() == null || e.getSociety().isActive())
                .limit(UPCOMING_EVENTS).map(PublicSiteService::toView).toList();
        List<SeasonCard> seasons = seasonRepository
                .findBySociety_StatusAndStatusInOrderByStartDateAsc(SocietyStatus.ACTIVE, OPEN_SEASONS).stream()
                .limit(UPCOMING_SEASONS).map(PublicSiteService::toCard).toList();
        // A society-scoped event only appears while its society is ACTIVE.
        List<EventView> thumbnails = events.findTop6ByPublishedTrueAndThumbnailUrlIsNotNullOrderByCreatedAtDesc().stream()
                .filter(e -> e.getSociety() == null || e.getSociety().isActive())
                .map(PublicSiteService::toView).toList();
        List<SponsorView> sponsorViews = sponsors.findByActiveTrueAndSociety_StatusOrderByAmountDesc(SocietyStatus.ACTIVE)
                .stream().map(s -> toView(s, s.getSociety().getName())).toList();
        List<WinnerView> winners = awards.findTop10BySociety_StatusOrderByCreatedAtDesc(SocietyStatus.ACTIVE)
                .stream().map(PublicSiteService::toWinner).toList();
        List<SocietyCard> societies = societyService.listAll().stream().filter(Society::isActive)
                .map(s -> new SocietyCard(s.getName(), s.getSlug(), s.getCity(), s.getLogoUrl(), s.getDescription())).toList();
        return new PublicViews.PlatformHomeView(upcoming, seasons, sponsorViews, winners, thumbnails, societies, PublicViews.plans());
    }

    // ------------------------------------------------------------------ society pages

    @Transactional(readOnly = true)
    public PublicViews.SocietyHomeView societyHome(String slug) {
        Society society = societyService.getPublicBySlug(slug);
        Long id = society.getId();
        Season season = seasonService.currentOrNull(id);
        if (season == null) {
            List<Season> all = seasonService.list(id);
            season = all.isEmpty() ? null : all.get(0);
        }
        List<StandingRow> standings = season == null ? List.of() : matchService.standings(id, season.getId());
        List<TeamView> teams = season == null ? List.of() : teamService.forSeason(id, season.getId()).stream()
                .map(PublicSiteService::toView).toList();
        List<Match> allMatches = matchService.forSociety(id);
        List<MatchView> recent = allMatches.stream().filter(Match::isCompleted)
                .sorted(Comparator.comparing(Match::getMatchDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECENT_RESULTS).map(PublicSiteService::toView).toList();
        List<MatchView> upcoming = matchService.upcoming(id).stream().limit(UPCOMING_FIXTURES)
                .map(PublicSiteService::toView).toList();
        return new PublicViews.SocietyHomeView(id, society.getName(), society.getSlug(), society.getDescription(),
                society.getCity(), society.getLogoUrl(), season == null ? null : toView(season), standings, upcoming, recent,
                teams, gallery.findTop12BySociety_IdOrderByCreatedAtDesc(id).stream()
                .map(g -> new GalleryView(g.getId(), g.getTitle(), g.getImageUrl(), g.getCaption())).toList(),
                awards.findBySociety_IdOrderByCreatedAtDesc(id).stream().map(PublicSiteService::toWinner).toList(),
                sponsors.findBySociety_IdAndActiveTrueOrderByAmountDesc(id).stream()
                        .map(s -> toView(s, society.getName())).toList(),
                events.findBySociety_IdAndPublishedTrueAndEventDateAfterOrderByEventDateAsc(id, LocalDateTime.now())
                        .stream().map(PublicSiteService::toView).toList());
    }

    @Transactional(readOnly = true)
    public List<MatchView> societyMatches(String slug) {
        Society society = societyService.getPublicBySlug(slug);
        return matchService.forSociety(society.getId()).stream().map(PublicSiteService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<SeasonView> societySeasons(String slug) {
        Society society = societyService.getPublicBySlug(slug);
        return seasonService.list(society.getId()).stream().map(PublicSiteService::toView).toList();
    }

    @Transactional(readOnly = true)
    public PublicViews.RegisterView registerOptions(String slug) {
        Society society = societyService.getPublicBySlug(slug);
        List<SeasonView> open = seasonService.list(society.getId()).stream()
                .filter(s -> Boolean.TRUE.equals(s.getRegistrationOpen()))
                .map(PublicSiteService::toView).toList();
        return new PublicViews.RegisterView(society.getName(), society.getSlug(), open);
    }

    @Transactional(readOnly = true)
    public PublicViews.SeasonDetailView seasonDetail(String slug, Long seasonId) {
        Society society = societyService.getPublicBySlug(slug);
        Season season = seasonService.get(society.getId(), seasonId);
        return new PublicViews.SeasonDetailView(society.getName(), society.getSlug(), toView(season),
                matchService.standings(society.getId(), seasonId),
                teamService.forSeason(society.getId(), seasonId).stream().map(PublicSiteService::toView).toList(),
                matchService.forSeason(society.getId(), seasonId).stream().map(PublicSiteService::toView).toList(),
                awards.findBySeason_IdOrderByCreatedAtDesc(seasonId).stream()
                        // The season id came from the URL, so confirm the award belongs to this society.
                        .filter(a -> society.getId().equals(a.getSocietyId()))
                        .map(PublicSiteService::toWinner).toList());
    }

    /** Public match page: the match's own society must be active, wherever the id came from. */
    @Transactional(readOnly = true)
    public PublicViews.MatchDetailView matchDetail(Long matchId) {
        Match m = matchService.publicDetail(matchId);
        if (!m.getSociety().isActive() || m.getStatus() == MatchStatus.CANCELLED && m.getMatchDate() == null) {
            throw new NotFoundException("Match not found");
        }
        return new PublicViews.MatchDetailView(m.getSociety().getName(), m.getSociety().getSlug(),
                m.getSeason().getName(), toView(m));
    }

    // ------------------------------------------------------------------ players (PII stripped in PlayerService)

    @Transactional(readOnly = true)
    public List<PublicPlayerView> players(String slug) {
        return playerService.publicRoster(societyService.getPublicBySlug(slug).getId());
    }

    /** viewer may be null (anonymous). PII is included only when PlayerService decides the viewer is entitled. */
    @Transactional(readOnly = true)
    public PlayerProfileView playerProfile(User viewerOrNull, String slug, Long playerId) {
        return playerService.profile(viewerOrNull, societyService.getPublicBySlug(slug).getId(), playerId);
    }

    // ------------------------------------------------------------------ mapping

    private static EventView toView(PlatformEvent e) {
        Society s = e.getSociety();
        return new EventView(e.getId(), e.getTitle(), e.getDescription(), e.getEventDate(), e.getType().getLabel(),
                e.getThumbnailUrl(), e.getExternalLink(), s == null ? null : s.getName(), s == null ? null : s.getSlug());
    }

    /** The sponsorship amount is a private figure and deliberately has no field on SponsorView. */
    private static SponsorView toView(Sponsor s, String societyName) {
        return new SponsorView(s.getName(), s.getTier() == null ? null : s.getTier().getLabel(), s.getLogoUrl(),
                s.getWebsite(), societyName);
    }

    private static WinnerView toWinner(Award a) {
        String winner = a.getPlayer() != null ? a.getPlayer().getName() : a.getTeam() != null ? a.getTeam().getName() : "";
        return new WinnerView(a.getTitle(), a.getCategory() == null ? null : a.getCategory().getLabel(), winner,
                a.getImageUrl(), a.getSociety().getName(), a.getSociety().getSlug(),
                a.getSeason() == null ? null : a.getSeason().getName());
    }

    private static TeamView toView(Team t) {
        return new TeamView(t.getId(), t.getName(), t.getDisplayShortName(), t.getColor(), t.getLogoUrl(),
                t.getCaptain() == null ? null : t.getCaptain().getName());
    }

    /** Planned budget and team purse are private, so SeasonView has no field for them. */
    private static SeasonView toView(Season s) {
        return new SeasonView(s.getId(), s.getName(), s.getYear(), s.getStatus().getLabel(), s.getStartDate(),
                s.getEndDate(), Boolean.TRUE.equals(s.getRegistrationOpen()), s.getOversPerMatch(), s.getDescription());
    }

    private static SeasonCard toCard(Season s) {
        Society society = s.getSociety();
        return new SeasonCard(s.getId(), s.getName(), s.getStatus().getLabel(), s.getStartDate(), s.getEndDate(),
                Boolean.TRUE.equals(s.getRegistrationOpen()), society.getName(), society.getSlug(), society.getLogoUrl());
    }

    public static MatchView toView(Match m) {
        return new MatchView(m.getId(), m.getTeamA().getName(), m.getTeamB().getName(), m.getTeamA().getColor(),
                m.getTeamB().getColor(), m.getMatchDate(), m.getVenue(), m.getStage() == null ? null : m.getStage().getLabel(),
                m.getStatus().getLabel(), m.getTeamARuns(), m.getTeamAWickets(), m.getTeamAOvers(), m.getTeamBRuns(),
                m.getTeamBWickets(), m.getTeamBOvers(), m.getWinnerTeam() == null ? null : m.getWinnerTeam().getName(),
                m.getResultSummary());
    }
}