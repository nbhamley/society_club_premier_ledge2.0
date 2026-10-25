package com.scpl.service;

import com.scpl.dto.SeasonRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AdminApproval;
import com.scpl.model.ApprovalEntityType;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.Season;
import com.scpl.model.SeasonStatus;
import com.scpl.model.Society;
import com.scpl.model.User;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.util.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SeasonService {

    private final SeasonRepository seasons;
    private final SocietyRepository societies;
    private final QuotaService quota;
    private final AdminApprovalService approvals;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<Season> list(Long societyId) {
        return seasons.findBySociety_IdOrderByCreatedAtDesc(societyId);
    }

    @Transactional(readOnly = true)
    public Season get(Long societyId, Long seasonId) {
        return seasons.findByIdAndSociety_Id(seasonId, societyId)
                .orElseThrow(() -> new NotFoundException("Season not found"));
    }

    /** The season shown on the society's public home: the latest one that is not finished. */
    @Transactional(readOnly = true)
    public Season currentOrNull(Long societyId) {
        return seasons.findFirstBySociety_IdAndStatusInOrderByStartDateDesc(societyId,
                EnumSet.complementOf(EnumSet.of(SeasonStatus.COMPLETED))).orElse(null);
    }

    @Transactional
    public Season create(User actor, Long societyId, SeasonRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertCanOpenSeason(societyId);
        if (seasons.existsBySociety_IdAndNameIgnoreCase(societyId, r.name().trim())) {
            throw new BusinessRuleException("A season named '" + r.name().trim() + "' already exists");
        }
        Society society = societies.getReferenceById(societyId);
        Season s = new Season();
        s.setSociety(society);
        apply(s, r);
        s = seasons.save(s);
        audit.record(actor, societyId, AuditActions.SEASON_CREATED, "SEASON", s.getId(), null,
                Map.of("name", s.getName()));
        return s;
    }

    @Transactional
    public Season update(User actor, Long societyId, Long seasonId, SeasonRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        Season s = get(societyId, seasonId);
        Map<String, Object> before = Map.of("name", s.getName(), "status", s.getStatus().name());
        apply(s, r);
        seasons.save(s);
        audit.record(actor, societyId, AuditActions.SEASON_UPDATED, "SEASON", seasonId, before,
                Map.of("name", s.getName(), "status", s.getStatus().name()));
        return s;
    }

    @Transactional
    public Season setStatus(User actor, Long societyId, Long seasonId, SeasonStatus status) {
        Tenancy.requireAdminOf(actor, societyId);
        Season s = get(societyId, seasonId);
        SeasonStatus before = s.getStatus();
        if (before == status) {
            return s;
        }
        // Re-opening a finished season would count against the concurrent-season quota again.
        if (before == SeasonStatus.COMPLETED) {
            quota.assertCanOpenSeason(societyId);
        }
        s.setStatus(status);
        // registrationOpen is derived from the status so the two can never disagree.
        s.setRegistrationOpen(status == SeasonStatus.REGISTRATION_OPEN);
        seasons.save(s);
        audit.record(actor, societyId, AuditActions.SEASON_STATUS_CHANGED, "SEASON", seasonId,
                Map.of("status", before.name()), Map.of("status", status.name()));
        return s;
    }

    /** Deleting a season cascades to teams, matches and lots, so it needs a 3-of-N approval. */
    @Transactional
    public AdminApproval requestDelete(User actor, Long societyId, Long seasonId, String reason) {
        Tenancy.requireAdminOf(actor, societyId);
        Season s = get(societyId, seasonId);
        String notes = "Delete season '" + s.getName() + "'" + (Text.clean(reason) != null ? ": " + reason.trim() : "");
        return approvals.raise(actor, societyId, ApprovalEntityType.SEASON_DELETE, seasonId, null, notes, null);
    }

    @EventListener
    @Transactional
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        AdminApproval a = event.approval();
        if (a.getStatus() != ApprovalStatus.APPROVED || !ApprovalEntityType.SEASON_DELETE.equals(a.getEntityType())) {
            return;
        }
        Season s = get(a.getSocietyId(), a.getEntityId());
        Map<String, Object> before = Map.of("name", s.getName(), "status", s.getStatus().name());
        // The database cascades to teams, matches, lots and registrations (ON DELETE CASCADE).
        seasons.delete(s);
        audit.record(event.decidedBy(), a.getSocietyId(), AuditActions.SEASON_DELETED, "SEASON", a.getEntityId(),
                before, null);
    }

    private void apply(Season s, SeasonRequest r) {
        s.setName(r.name().trim());
        s.setYear(r.year());
        s.setStartDate(r.startDate());
        s.setEndDate(r.endDate());
        if (r.startDate() != null && r.endDate() != null && r.endDate().isBefore(r.startDate())) {
            throw new BusinessRuleException("The end date cannot be before the start date");
        }
        int overs = r.oversPerMatch() == null ? 10 : r.oversPerMatch();
        if (overs < 1 || overs > 50) {
            throw new BusinessRuleException("Overs per match must be between 1 and 50");
        }
        s.setOversPerMatch(overs);
        s.setTeamPurse(r.teamPurse() == null ? 0.0 : Math.max(0.0, r.teamPurse()));
        s.setPlannedBudget(r.plannedBudget() == null ? 0.0 : Math.max(0.0, r.plannedBudget()));
        s.setDescription(Text.clean(r.description()));
    }
}