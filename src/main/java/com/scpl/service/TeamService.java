package com.scpl.service;

import com.scpl.dto.TeamRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.Player;
import com.scpl.model.Season;
import com.scpl.model.Team;
import com.scpl.model.User;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.util.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.scpl.util.Urls;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teams;
    private final SeasonRepository seasons;
    private final PlayerRepository players;
    private final MatchRepository matches;
    private final QuotaService quota;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<Team> forSeason(Long societyId, Long seasonId) {
        return teams.findBySeason_IdOrderByNameAsc(seasonId).stream()
                .filter(t -> societyId.equals(t.getSocietyId())).toList();
    }

    @Transactional(readOnly = true)
    public List<Team> forSociety(Long societyId) {
        return teams.findBySociety_IdOrderByNameAsc(societyId);
    }

    @Transactional(readOnly = true)
    public Team get(Long societyId, Long teamId) {
        return teams.findByIdAndSociety_Id(teamId, societyId)
                .orElseThrow(() -> new NotFoundException("Team not found"));
    }

    @Transactional
    public Team create(User actor, Long societyId, Long seasonId, TeamRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        Season season = seasons.findByIdAndSociety_Id(seasonId, societyId)
                .orElseThrow(() -> new NotFoundException("Season not found"));
        quota.assertCanAddTeam(societyId, seasonId);
        if (teams.existsBySeason_IdAndNameIgnoreCase(seasonId, r.name().trim())) {
            throw new BusinessRuleException("This season already has a team named '" + r.name().trim() + "'");
        }
        Team t = new Team();
        t.setSociety(season.getSociety());
        t.setSeason(season);
        apply(t, r);
        t.setPurseRemaining(season.getTeamPurse());
        t = teams.save(t);
        audit.record(actor, societyId, AuditActions.TEAM_CREATED, "TEAM", t.getId(), null, Map.of("name", t.getName()));
        return t;
    }

    @Transactional
    public Team update(User actor, Long societyId, Long teamId, TeamRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        Team t = get(societyId, teamId);
        Map<String, Object> before = Map.of("name", t.getName());
        apply(t, r);
        teams.save(t);
        audit.record(actor, societyId, AuditActions.TEAM_UPDATED, "TEAM", teamId, before, Map.of("name", t.getName()));
        return t;
    }

    @Transactional
    public Team setCaptain(User actor, Long societyId, Long teamId, Long playerId) {
        Tenancy.requireAdminOf(actor, societyId);
        Team t = get(societyId, teamId);
        if (playerId == null) {
            t.setCaptain(null);
        } else {
            Player p = players.findByIdAndSociety_Id(playerId, societyId)
                    .orElseThrow(() -> new NotFoundException("Player not found"));
            if (!teamId.equals(p.getTeamId())) {
                throw new BusinessRuleException("The captain must be a player of this team");
            }
            t.setCaptain(p);
        }
        return teams.save(t);
    }

    @Transactional
    public void delete(User actor, Long societyId, Long teamId) {
        Tenancy.requireAdminOf(actor, societyId);
        Team t = get(societyId, teamId);
        // Refuse rather than orphan: unassigning players or dropping fixtures silently would
        // corrupt auction results and standings.
        if (!players.findByTeam_IdOrderByNameAsc(teamId).isEmpty()) {
            throw new BusinessRuleException("This team still has players. Move or release them first.");
        }
        if (matches.existsByTeamA_IdOrTeamB_Id(teamId, teamId)) {
            throw new BusinessRuleException("This team has fixtures. Delete or reassign them first.");
        }
        teams.delete(t);
        audit.record(actor, societyId, AuditActions.TEAM_DELETED, "TEAM", teamId, Map.of("name", t.getName()), null);
    }

    private void apply(Team t, TeamRequest r) {
        t.setName(r.name().trim());
        t.setShortName(Text.clean(r.shortName()));
        String color = Text.clean(r.color());
        if (color != null && !color.matches("#[0-9a-fA-F]{6}")) {
            throw new BusinessRuleException("Colour must look like #1e88e5");
        }
        t.setColor(color);
        t.setLogoUrl(Urls.requireWebUrl(r.logoUrl(), "Logo"));
    }
}