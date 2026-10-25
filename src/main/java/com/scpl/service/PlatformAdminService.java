package com.scpl.service;

import com.scpl.dto.NewAdminRequest;
import com.scpl.dto.PlatformViews.AuditPageView;
import com.scpl.dto.PlatformViews.AuditRow;
import com.scpl.dto.PlatformViews.DashboardView;
import com.scpl.dto.PlatformViews.EventRow;
import com.scpl.dto.PlatformViews.SocietyOption;
import com.scpl.dto.PlatformViews.SocietyRow;
import com.scpl.dto.PlatformViews.Stats;
import com.scpl.dto.PlatformViews.SubscriptionRow;
import com.scpl.dto.SocietyRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.AuditLog;
import com.scpl.model.PlatformEvent;
import com.scpl.model.Society;
import com.scpl.model.Subscription;
import com.scpl.model.SubscriptionStatus;
import com.scpl.model.User;
import com.scpl.repo.AdminApprovalRepository;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.PlatformEventRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything the platform console reads or does on top of the existing services. Read models are built inside one
 * read-only transaction from counts and billing state only (§2: aggregate stats, never personal player data).
 * Do not catch exceptions from other @Transactional services in here: it would mark the shared transaction rollback-only.
 */
@Service
@RequiredArgsConstructor
public class PlatformAdminService {

    private static final int AUDIT_PAGE_SIZE = 50;
    private static final int RECENT_AUDIT = 10;
    private static final int DETAIL_LIMIT = 200;

    public record CreatedSociety(Long id, String name, String slug, String statusLabel) {
    }

    private final SocietyService societyService;
    private final SubscriptionService subscriptions;
    private final ContentService content;
    private final AuditService audit;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final MatchRepository matches;
    private final AdminApprovalRepository approvalRepo;
    private final PlatformEventRepository eventRepo;

    @Value("${scpl.approval.min-admins-to-activate:3}")
    private int minAdmins;
    @Value("${feature.cross-society.enabled:false}")
    private boolean crossSocietyEnabled;

    // ------------------------------------------------------------------ dashboard

    @Transactional(readOnly = true)
    public DashboardView dashboard(User actor) {
        Tenancy.requirePlatformAdmin(actor);
        List<Society> all = societyService.listAll();
        Map<Long, Subscription> current = currentSubscriptions(actor);
        LocalDateTime now = LocalDateTime.now();

        long trial = countStatus(current, SubscriptionStatus.TRIAL);
        long active = countStatus(current, SubscriptionStatus.ACTIVE);
        long pastDue = countStatus(current, SubscriptionStatus.PAST_DUE);
        long cancelled = countStatus(current, SubscriptionStatus.CANCELLED);
        double mrr = current.values().stream().filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE)
                .mapToDouble(Subscription::getMonthlyCost).sum();
        long upcoming = eventRepo.findAllByOrderByEventDateDesc().stream()
                .filter(e -> Boolean.TRUE.equals(e.getPublished()) && e.getEventDate() != null && e.getEventDate().isAfter(now))
                .count();
        Stats stats = new Stats(all.size(), all.stream().filter(Society::isActive).count(),
                all.stream().filter(s -> s.getStatus() == com.scpl.model.SocietyStatus.DRAFT).count(),
                all.stream().filter(s -> s.getStatus() == com.scpl.model.SocietyStatus.SUSPENDED).count(),
                teams.count(), players.countByActiveTrue(), matches.count(), trial, active, pastDue, cancelled, mrr,
                approvalRepo.countByStatus(ApprovalStatus.PENDING), upcoming);

        List<SocietyRow> awaiting = all.stream().filter(s -> s.getStatus() == com.scpl.model.SocietyStatus.DRAFT)
                .map(s -> toRow(s, current.get(s.getId()))).toList();
        List<SubscriptionRow> attention = current.values().stream()
                .filter(s -> s.getStatus() == SubscriptionStatus.PAST_DUE || s.getStatus() == SubscriptionStatus.CANCELLED)
                .map(PlatformAdminService::toRow).toList();
        Map<Long, String> names = societyNames(all);
        List<AuditRow> recent = audit.all(PageRequest.of(0, RECENT_AUDIT)).getContent().stream()
                .map(l -> toRow(l, names)).toList();
        return new DashboardView(stats, awaiting, attention, recent, crossSocietyEnabled);
    }

    // ------------------------------------------------------------------ societies

    /** Per-society counts are one query each; fine for the expected number of tenants, replace with a grouped query if it grows. */
    @Transactional(readOnly = true)
    public List<SocietyRow> societies(User actor) {
        Tenancy.requirePlatformAdmin(actor);
        Map<Long, Subscription> current = currentSubscriptions(actor);
        return societyService.listAll().stream().map(s -> toRow(s, current.get(s.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<SocietyOption> societyOptions(User actor) {
        Tenancy.requirePlatformAdmin(actor);
        return societyService.listAll().stream().map(s -> new SocietyOption(s.getId(), s.getName())).toList();
    }

    /**
     * Creation wizard (§6): a society needs at least the minimum number of founding admins, so one with fewer can never
     * exist, let alone be activated. A trial starts immediately. With activate=true the society goes live in the same step.
     */
    @Transactional
    public CreatedSociety createSociety(User actor, SocietyRequest req, List<NewAdminRequest> admins, boolean activate) {
        Tenancy.requirePlatformAdmin(actor);
        if (admins == null || admins.size() < minAdmins) {
            throw new BusinessRuleException("A society needs at least " + minAdmins + " admins");
        }
        Society s = societyService.createWithAdmins(req, admins, actor);
        subscriptions.startTrial(s, null);
        if (activate) {
            societyService.activate(s.getId(), actor);
        }
        return new CreatedSociety(s.getId(), s.getName(), s.getSlug(), s.getStatus().getLabel());
    }

    // ------------------------------------------------------------------ subscriptions

    @Transactional(readOnly = true)
    public List<SubscriptionRow> subscriptionRows(User actor) {
        return subscriptions.listAll(actor).stream().map(PlatformAdminService::toRow).toList();
    }

    // ------------------------------------------------------------------ events

    @Transactional(readOnly = true)
    public List<EventRow> eventRows(User actor) {
        return content.events(actor).stream().map(PlatformAdminService::toRow).toList();
    }

    // ------------------------------------------------------------------ audit

    @Transactional(readOnly = true)
    public AuditPageView auditPage(User actor, int page, Long societyIdOrNull) {
        Tenancy.requirePlatformAdmin(actor);
        int index = Math.max(0, page);
        PageRequest pageable = PageRequest.of(index, AUDIT_PAGE_SIZE);
        Page<AuditLog> logs = societyIdOrNull == null ? audit.all(pageable) : audit.forSociety(societyIdOrNull, pageable);
        Map<Long, String> names = societyNames(societyService.listAll());
        return new AuditPageView(logs.getContent().stream().map(l -> toRow(l, names)).toList(), index,
                Math.max(1, logs.getTotalPages()), societyIdOrNull);
    }

    // ------------------------------------------------------------------ helpers

    /** Newest subscription per society: listAll is ordered newest first, so the first one seen wins. */
    private Map<Long, Subscription> currentSubscriptions(User actor) {
        Map<Long, Subscription> map = new LinkedHashMap<>();
        for (Subscription s : subscriptions.listAll(actor)) {
            map.putIfAbsent(s.getSocietyId(), s);
        }
        return map;
    }

    private static long countStatus(Map<Long, Subscription> current, SubscriptionStatus status) {
        return current.values().stream().filter(s -> s.getStatus() == status).count();
    }

    private static Map<Long, String> societyNames(List<Society> all) {
        return all.stream().collect(Collectors.toMap(Society::getId, Society::getName, (a, b) -> a, LinkedHashMap::new));
    }

    private SocietyRow toRow(Society s, Subscription sub) {
        return new SocietyRow(s.getId(), s.getName(), s.getSlug(), s.getCity(), s.getStatus(), s.getStatus().getLabel(),
                s.getAdminCount(), s.getStatus() != com.scpl.model.SocietyStatus.ACTIVE && s.hasMinimumAdmins(minAdmins),
                sub == null ? null : sub.getPlan().getLabel(), sub == null ? null : sub.getStatus().getLabel(),
                teams.countBySociety_Id(s.getId()), players.countBySociety_IdAndActiveTrue(s.getId()), s.getCreatedAt());
    }

    private static SubscriptionRow toRow(Subscription s) {
        return new SubscriptionRow(s.getId(), s.getSocietyId(), s.getSociety().getName(), s.getPlan().getLabel(),
                s.getStatus(), s.getStatus().getLabel(), s.getMonthlyCost(), s.getCurrency(), s.getStartedAt(),
                s.getExpiresAt(), s.getNextBillingAt(), s.getLastPaymentAt(), s.getPastDueSince(),
                Boolean.TRUE.equals(s.getAutoRenew()));
    }

    private static EventRow toRow(PlatformEvent e) {
        return new EventRow(e.getId(), e.getTitle(), e.getDescription(), e.getEventDate(), e.getScope(),
                e.getScope().getLabel(), e.getType(), e.getType().getLabel(), e.getSocietyId(),
                e.getSociety() == null ? null : e.getSociety().getName(), e.getThumbnailUrl(), e.getExternalLink(),
                Boolean.TRUE.equals(e.getPublished()));
    }

    private static AuditRow toRow(AuditLog l, Map<Long, String> societyNames) {
        String who = l.getUser() == null ? "system"
                : l.getUser().getFullName() != null && !l.getUser().getFullName().isBlank() ? l.getUser().getFullName()
                : l.getUser().getUsername();
        String detail = l.getNewValue() == null ? null
                : l.getNewValue().length() > DETAIL_LIMIT ? l.getNewValue().substring(0, DETAIL_LIMIT) + "…" : l.getNewValue();
        return new AuditRow(l.getTimestamp(), who, l.getSocietyId(),
                l.getSocietyId() == null ? null : societyNames.get(l.getSocietyId()), l.getAction(), l.getEntityType(),
                l.getEntityId(), detail);
    }
}