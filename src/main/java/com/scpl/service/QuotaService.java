package com.scpl.service;

import com.scpl.exception.QuotaExceededException;
import com.scpl.model.SeasonStatus;
import com.scpl.model.SubscriptionPlan;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SubscriptionRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.util.PlanCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/** Plan limit checks. A society with no subscription row yet is treated as being on the free trial. */
@Service
@RequiredArgsConstructor
public class QuotaService {

    private static final Set<SeasonStatus> CONCURRENT = EnumSet.complementOf(EnumSet.of(SeasonStatus.COMPLETED));

    private final SubscriptionRepository subscriptions;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final SeasonRepository seasons;

    @Transactional(readOnly = true)
    public PlanCatalog.Limits limitsFor(Long societyId) {
        return subscriptions.findFirstBySociety_IdOrderByCreatedAtDesc(societyId)
                .map(s -> PlanCatalog.of(s.getPlan()))
                .orElse(PlanCatalog.of(SubscriptionPlan.FREE_TRIAL));
    }

    @Transactional(readOnly = true)
    public void assertCanAddTeam(Long societyId, Long seasonId) {
        int max = limitsFor(societyId).maxTeams();
        if (teams.countBySeason_Id(seasonId) >= max) {
            throw new QuotaExceededException("Your plan allows up to " + max + " teams per season. Upgrade to add more.");
        }
    }

    @Transactional(readOnly = true)
    public void assertCanAddPlayers(Long societyId, int additional) {
        int max = limitsFor(societyId).maxPlayers();
        // long arithmetic: UNLIMITED is Integer.MAX_VALUE and adding to it must not overflow.
        if (players.countBySociety_IdAndActiveTrue(societyId) + (long) additional > max) {
            throw new QuotaExceededException("Your plan allows up to " + max + " players. Upgrade to add more.");
        }
    }

    @Transactional(readOnly = true)
    public void assertCanOpenSeason(Long societyId) {
        int max = limitsFor(societyId).maxConcurrentSeasons();
        if (seasons.countBySociety_IdAndStatusIn(societyId, CONCURRENT) >= max) {
            throw new QuotaExceededException("Your plan allows " + max
                    + " concurrent season(s). Complete a season or upgrade to start another.");
        }
    }

    @Transactional(readOnly = true)
    public void assertVendorModule(Long societyId) {
        if (!limitsFor(societyId).vendorModule()) {
            throw new QuotaExceededException("Vendors and bills are included from the Standard plan.");
        }
    }

    @Transactional(readOnly = true)
    public void assertAiImport(Long societyId) {
        if (!limitsFor(societyId).aiImport()) {
            throw new QuotaExceededException("AI-assisted import is included from the Standard plan.");
        }
    }
}