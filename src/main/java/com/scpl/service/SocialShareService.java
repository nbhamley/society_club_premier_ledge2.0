package com.scpl.service;

import com.scpl.dto.ShareViews;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.exception.TenantAccessException;
import com.scpl.model.Award;
import com.scpl.model.Match;
import com.scpl.model.Player;
import com.scpl.model.Season;
import com.scpl.model.SharePlatform;
import com.scpl.model.Society;
import com.scpl.model.SocialShare;
import com.scpl.model.Team;
import com.scpl.model.User;
import com.scpl.repo.AwardRepository;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocialShareRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.repo.UserRepository;
import com.scpl.util.Text;
import com.scpl.util.Urls;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Share flow (§13). Only the data's owner may share: the player themselves, or an admin of the
 * owning society. Share links point at /share/{token}, not at guessable entity ids, so a link
 * can be revoked later and a landing page only ever shows stats-only public pages.
 */
@Service
@RequiredArgsConstructor
public class SocialShareService {

    public static final Set<String> TYPES = Set.of("player", "team", "match", "award", "season");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_CAPTION = 500;
    private static final int X_TEXT_LIMIT = 240;

    private final SocialShareRepository shares;
    private final PlayerRepository players;
    private final TeamRepository teams;
    private final MatchRepository matches;
    private final AwardRepository awards;
    private final SeasonRepository seasons;
    private final SocietyRepository societies;
    private final UserRepository users;
    private final AuditService audit;

    @Value("${scpl.public-base-url:http://localhost:8080}")
    private String baseUrl;

    /** What the confirmation dialog shows. Stores nothing. */
    @Transactional(readOnly = true)
    public ShareViews.Preview preview(User actor, SharePlatform platform, String type, Long targetId) {
        Subject s = resolve(actor, type, targetId);
        return new ShareViews.Preview(confirmation(s, platform), defaultCaption(s), s.imageUrl);
    }

    @Transactional
    public ShareViews.Result create(User actor, SharePlatform platform, String type, Long targetId,
                                    String captionOverride) {
        Subject subject = resolve(actor, type, targetId);
        String caption = Text.clean(captionOverride) != null ? captionOverride.trim() : defaultCaption(subject);
        if (caption.length() > MAX_CAPTION) {
            caption = caption.substring(0, MAX_CAPTION);
        }
        String token = newToken();
        String shareUrl = trimSlash(baseUrl) + "/share/" + token;

        SocialShare row = new SocialShare();
        row.setToken(token);
        row.setUser(users.getReferenceById(actor.getId()));
        row.setPlatform(platform);
        row.setCaption(caption);
        row.setImageUrl(subject.imageUrl);
        row.setShareUrl(shareUrl);
        subject.attach.accept(row);
        row = shares.save(row);
        audit.record(actor, actor.getSocietyId(), AuditActions.SHARE_CREATED, "SHARE", row.getId(), null,
                Map.of("type", type, "targetId", targetId, "platform", platform.name()));

        boolean modal = platform == SharePlatform.INSTAGRAM || platform == SharePlatform.FACEBOOK;
        return new ShareViews.Result(token, shareUrl, caption, subject.imageUrl, platform.getLabel(),
                outbound(platform, caption, shareUrl), modal);
    }

    /** Public landing page. A suspended or draft society's shares look like they never existed. */
    @Transactional(readOnly = true)
    public ShareViews.Landing landing(String token) {
        SocialShare s = shares.findByToken(token).orElseThrow(() -> new NotFoundException("Share not found"));
        Society society;
        String path;
        String title;
        if (s.getPlayer() != null) {
            Player p = s.getPlayer();
            society = p.getSociety();
            title = p.getName();
            path = "/society/" + society.getSlug() + "/player/" + p.getId();
        } else if (s.getMatch() != null) {
            Match m = s.getMatch();
            society = m.getSociety();
            title = m.getTeamA().getName() + " vs " + m.getTeamB().getName();
            path = "/matches/" + m.getId();
        } else if (s.getAward() != null) {
            Award a = s.getAward();
            society = a.getSociety();
            title = a.getTitle();
            path = a.getSeason() != null ? "/society/" + society.getSlug() + "/seasons/" + a.getSeason().getId()
                    : "/society/" + society.getSlug();
        } else if (s.getTeam() != null) {
            Team t = s.getTeam();
            society = t.getSociety();
            title = t.getName();
            path = "/society/" + society.getSlug() + "/seasons/" + t.getSeason().getId();
        } else if (s.getSeason() != null) {
            Season season = s.getSeason();
            society = season.getSociety();
            title = season.getName();
            path = "/society/" + society.getSlug() + "/seasons/" + season.getId();
        } else {
            throw new NotFoundException("Share not found");
        }
        if (!society.isActive()) {
            throw new NotFoundException("Share not found");
        }
        return new ShareViews.Landing(title, s.getCaption(), s.getImageUrl(), s.getShareUrl(), path, society.getName());
    }

    // ------------------------------------------------------------------ internals

    private static final class Subject {
        String name;
        String statLine;
        String imageUrl;
        String societyName;
        Consumer<SocialShare> attach;
    }

    /** Loads the target scoped to the actor's own society and checks ownership. A foreign id is a 404. */
    private Subject resolve(User actor, String type, Long targetId) {
        if (actor == null || actor.getSocietyId() == null) {
            throw new TenantAccessException("Only society members can share");
        }
        if (type == null || !TYPES.contains(type) || targetId == null) {
            throw new BusinessRuleException("Unknown share type");
        }
        Long societyId = actor.getSocietyId();
        boolean admin = actor.isSocietyAdmin() && Boolean.TRUE.equals(actor.getEnabled());
        // Reloaded rather than read from actor.getPlayer(): the actor is detached and that proxy is lazy.
        Player self = actor.getPlayerId() == null ? null
                : players.findWithDetailsByIdAndSociety_Id(actor.getPlayerId(), societyId).orElse(null);
        Long selfTeamId = self == null ? null : self.getTeamId();

        Subject sub = new Subject();
        sub.societyName = societies.findById(societyId).map(Society::getName)
                .orElseThrow(() -> new NotFoundException("Society not found"));

        switch (type) {
            case "player" -> {
                Player p = players.findWithDetailsByIdAndSociety_Id(targetId, societyId)
                        .filter(x -> Boolean.TRUE.equals(x.getActive()))
                        .orElseThrow(() -> new NotFoundException("Player not found"));
                if (!admin && (self == null || !self.getId().equals(p.getId()))) {
                    throw new TenantAccessException("You can only share your own profile");
                }
                sub.name = p.getName();
                sub.statLine = String.format(Locale.ROOT, "%d matches, %d runs, %d wickets%s", p.getMatchesPlayed(),
                        p.getRuns(), p.getWickets(), p.getTeam() == null ? "" : " for " + p.getTeam().getName());
                sub.imageUrl = p.getPhotoUrl();
                sub.attach = row -> row.setPlayer(p);
            }
            case "team" -> {
                Team t = teams.findByIdAndSociety_Id(targetId, societyId)
                        .orElseThrow(() -> new NotFoundException("Team not found"));
                if (!admin && (selfTeamId == null || !selfTeamId.equals(t.getId()))) {
                    throw new TenantAccessException("You can only share your own team");
                }
                sub.name = t.getName();
                sub.statLine = t.getSeason().getName() + " season";
                sub.imageUrl = t.getLogoUrl();
                sub.attach = row -> row.setTeam(t);
            }
            case "match" -> {
                Match m = matches.findByIdAndSociety_Id(targetId, societyId)
                        .orElseThrow(() -> new NotFoundException("Match not found"));
                boolean playing = selfTeamId != null
                        && (selfTeamId.equals(m.getTeamA().getId()) || selfTeamId.equals(m.getTeamB().getId()));
                if (!admin && !playing) {
                    throw new TenantAccessException("You can only share matches your team plays in");
                }
                sub.name = m.getTeamA().getName() + " vs " + m.getTeamB().getName();
                sub.statLine = m.getResultSummary() != null ? m.getResultSummary()
                        : m.isCompleted() ? "result" : "fixture";
                sub.attach = row -> row.setMatch(m);
            }
            case "award" -> {
                Award a = awards.findByIdAndSociety_Id(targetId, societyId)
                        .orElseThrow(() -> new NotFoundException("Award not found"));
                boolean recipient = self != null
                        && ((a.getPlayer() != null && a.getPlayer().getId().equals(self.getId()))
                        || (a.getTeam() != null && selfTeamId != null && a.getTeam().getId().equals(selfTeamId)));
                if (!admin && !recipient) {
                    throw new TenantAccessException("You can only share your own awards");
                }
                String winner = a.getPlayer() != null ? a.getPlayer().getName()
                        : a.getTeam() != null ? a.getTeam().getName() : "";
                sub.name = a.getTitle();
                sub.statLine = winner.isEmpty() ? "award" : "award won by " + winner;
                sub.imageUrl = a.getImageUrl();
                sub.attach = row -> row.setAward(a);
            }
            case "season" -> {
                Season season = seasons.findByIdAndSociety_Id(targetId, societyId)
                        .orElseThrow(() -> new NotFoundException("Season not found"));
                // Season pages are public data, so any registered player of the society may share one.
                if (!admin && self == null) {
                    throw new TenantAccessException("Only players and admins of this society can share");
                }
                sub.name = season.getName();
                sub.statLine = "season at " + sub.societyName;
                sub.attach = row -> row.setSeason(season);
            }
            default -> throw new BusinessRuleException("Unknown share type");
        }
        return sub;
    }

    private static String confirmation(Subject s, SharePlatform platform) {
        return "This share is public - you're about to publish " + s.name + "'s " + s.statLine
                + " to " + platform.getLabel() + ". Continue?";
    }

    private static String defaultCaption(Subject s) {
        return s.name + ": " + s.statLine + " | " + s.societyName;
    }

    /** Platform share endpoints. Instagram and Facebook cannot take prefilled text, so those open the app instead. */
    private static String outbound(SharePlatform p, String caption, String shareUrl) {
        return switch (p) {
            case WHATSAPP -> "https://wa.me/?text=" + Urls.encode(caption + " " + shareUrl);
            case TWITTER_X -> "https://twitter.com/intent/tweet?text=" + Urls.encode(limit(caption, X_TEXT_LIMIT))
                    + "&url=" + Urls.encode(shareUrl);
            case LINKEDIN -> "https://www.linkedin.com/sharing/share-offsite/?url=" + Urls.encode(shareUrl);
            case INSTAGRAM -> "https://www.instagram.com/";
            case FACEBOOK -> "https://www.facebook.com/";
            case COPY_LINK -> null;
        };
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}