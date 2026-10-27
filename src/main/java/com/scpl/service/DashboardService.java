package com.scpl.service;

import com.scpl.dto.DashboardViews.DashboardView;
import com.scpl.dto.DashboardViews.TeamHomeView;
import com.scpl.dto.PlayerProfileView;
import com.scpl.dto.PublicViews.MatchView;
import com.scpl.exception.TenantAccessException;
import com.scpl.model.Match;
import com.scpl.model.Player;
import com.scpl.model.Season;
import com.scpl.model.Society;
import com.scpl.model.Subscription;
import com.scpl.model.SubscriptionStatus;
import com.scpl.model.Team;
import com.scpl.model.User;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Read models for /dashboard and /me/team. Everything is scoped by the caller's own society id, which
 * comes from the freshly loaded User, never from a request parameter.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final SocietyService societies;
    private final SeasonService seasons;
    private final MatchService matchService;
    private final PlayerService playerService;
    private final AdminApprovalService approvals;
    private final SubscriptionService subscriptions;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final MatchRepository matches;

    @Transactional(readOnly = true)
    public DashboardView forUser(User u) {
        Long societyId = u.getSocietyId();
        if (societyId == null) {
            throw new TenantAccessException("Platform admins have no society dashboard");
        }
        Society society = societies.get(societyId);
        Season current = seasons.currentOrNull(societyId);
        boolean admin = u.isSocietyAdmin();

        long pending = admin ? approvals.pendingCount(societyId) : 0;
        long awaiting = admin ? approvals.awaitingVoteFrom(u).size() : 0;

        Optional<Match> next = matchService.nextFixture(societyId);
        String nextText = next.map(m -> m.getTeamA().getName() + " vs " + m.getTeamB().getName()).orElse(null);
        LocalDateTime nextDate = next.map(Match::getMatchDate).orElse(null);

        String plan = null;
        String subStatus = null;
        Long trialLeft = null;
        if (admin) {
            Subscription s = subscriptions.current(societyId).orElse(null);
            if (s != null) {
                plan = s.getPlan().getLabel();
                subStatus = s.getStatus().getLabel();
                if (s.getStatus() == SubscriptionStatus.TRIAL && s.getExpiresAt() != null) {
                    trialLeft = Math.max(0, ChronoUnit.DAYS.between(LocalDateTime.now(), s.getExpiresAt()));
                }
            }
        }
        PlayerProfileView own = u.getPlayerId() == null ? null : playerService.ownProfile(u);
        return new DashboardView(admin, society.getName(), society.getSlug(), society.getStatus().getLabel(),
                current == null ? null : current.getName(), teams.countBySociety_Id(societyId),
                players.countBySociety_IdAndActiveTrue(societyId), matches.countBySociety_Id(societyId), pending,
                awaiting, nextText, nextDate, plan, subStatus, trialLeft, subscriptions.isReadOnly(societyId), own);
    }

    /** Null when the user has no player profile or is not on a team yet (e.g. before the auction). */
    @Transactional(readOnly = true)
    public TeamHomeView teamHome(User u) {
        Long societyId = u.getSocietyId();
        if (societyId == null || u.getPlayerId() == null) {
            return null;
        }
        Player p = players.findByIdAndSociety_Id(u.getPlayerId(), societyId).orElse(null);
        if (p == null || p.getTeam() == null) {
            return null;
        }
        Team t = teams.findByIdAndSociety_Id(p.getTeam().getId(), societyId).orElse(null);
        if (t == null) {
            return null;
        }
        Long seasonId = t.getSeasonId();
        List<MatchView> fixtures = matchService.forSeason(societyId, seasonId).stream()
                .filter(m -> m.involves(t)).map(m -> PublicSiteService.toView(m)).toList();
        boolean captain = t.getCaptain() != null && t.getCaptain().getId().equals(u.getPlayerId());
        return new TeamHomeView(t.getId(), t.getName(), t.getDisplayShortName(), t.getColor(), t.getLogoUrl(),
                seasonId, t.getSeason().getName(), t.getCaptain() == null ? null : t.getCaptain().getName(), captain,
                playerService.teamRoster(societyId, t.getId()), matchService.standings(societyId, seasonId), fixtures);
    }
}