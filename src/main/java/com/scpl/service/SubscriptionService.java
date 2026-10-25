package com.scpl.service;

import com.scpl.dto.BillingView;
import com.scpl.dto.NewAdminRequest;
import com.scpl.dto.PublicViews;
import com.scpl.dto.SocietyRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.exception.QuotaExceededException;
import com.scpl.model.Invoice;
import com.scpl.model.InvoiceStatus;
import com.scpl.model.Season;
import com.scpl.model.SeasonStatus;
import com.scpl.model.Society;
import com.scpl.model.SocietyStatus;
import com.scpl.model.Subscription;
import com.scpl.model.SubscriptionPlan;
import com.scpl.model.SubscriptionStatus;
import com.scpl.model.User;
import com.scpl.repo.InvoiceRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.SubscriptionRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.service.PaymentService.PaymentResult;
import com.scpl.util.MiniPdf;
import com.scpl.util.PlanCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Trials, plan changes, payments and dunning (§6, §12).
 *
 * <p>Lifecycle: TRIAL (FREE_TRIAL limits) -> ACTIVE on first payment -> PAST_DUE when a charge
 * fails -> CANCELLED after the grace period. PAST_DUE and CANCELLED societies are read-only
 * (see isReadOnly) but keep read access and the right to export a final backup.
 * pastDueSince doubles as the "unpaid since" marker for cancelled societies.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final Set<SubscriptionPlan> PAID_PLANS =
            EnumSet.of(SubscriptionPlan.BASIC, SubscriptionPlan.STANDARD, SubscriptionPlan.PREMIUM);

    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final SocietyRepository societies;
    private final SeasonRepository seasons;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final SocietyService societyService;
    private final PaymentService payments;
    private final NotificationService notifications;
    private final AuditService audit;

    @Value("${scpl.subscription.trial-days:14}")
    private int trialDays;

    @Value("${scpl.subscription.read-only-after-past-due-days:30}")
    private int graceDays;

    @Value("${scpl.approval.min-admins-to-activate:3}")
    private int minAdmins;

    // ------------------------------------------------------------------ sign-up and trial

    /**
     * Public /subscribe flow: creates the society (DRAFT, so a platform admin still reviews it
     * before it appears publicly) with its founding admins and starts the trial.
     * chosen may be FREE_TRIAL or null, meaning "decide later".
     */
    @Transactional
    public Subscription signUp(SocietyRequest society, List<NewAdminRequest> admins, SubscriptionPlan chosen) {
        if (admins == null || admins.size() < minAdmins) {
            throw new BusinessRuleException("A society needs at least " + minAdmins + " admins to sign up");
        }
        Society s = societyService.createWithAdmins(society, admins, null);
        return startTrial(s, chosen != null && PAID_PLANS.contains(chosen) ? chosen : null);
    }

    @Transactional
    public Subscription startTrial(Society society, SubscriptionPlan chosenOrNull) {
        if (!subscriptions.findBySociety_IdOrderByCreatedAtDesc(society.getId()).isEmpty()) {
            throw new BusinessRuleException("This society has already had a subscription");
        }
        LocalDateTime now = LocalDateTime.now();
        Subscription s = new Subscription();
        s.setSociety(society);
        s.setPlan(SubscriptionPlan.FREE_TRIAL);
        s.setStatus(SubscriptionStatus.TRIAL);
        s.setStartedAt(now);
        s.setExpiresAt(now.plusDays(trialDays));
        s.setNextBillingAt(s.getExpiresAt());
        s.setMonthlyCost(0.0);
        s.setCurrency("INR");
        s.setAutoRenew(true);
        s.setNextPlan(chosenOrNull);
        s = subscriptions.save(s);
        audit.record(null, society.getId(), AuditActions.SUBSCRIPTION_STARTED, "SUBSCRIPTION", s.getId(), null,
                Map.of("trialDays", trialDays, "chosenPlan", chosenOrNull == null ? "none" : chosenOrNull.name()));
        return s;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Optional<Subscription> current(Long societyId) {
        return subscriptions.findFirstBySociety_IdOrderByCreatedAtDesc(societyId);
    }

    /**
     * Called by ReadOnlyGuard on every mutating request. A society with no subscription row is not
     * blocked. A SUSPENDED society is always read-only: suspension is the platform's lock switch.
     */
    @Transactional(readOnly = true)
    public boolean isReadOnly(Long societyId) {
        if (societies.findById(societyId).map(s -> s.getStatus() == SocietyStatus.SUSPENDED).orElse(false)) {
            return true;
        }
        return current(societyId).map(s -> s.isReadOnly(graceDays, LocalDateTime.now())).orElse(false);
    }

    @Transactional(readOnly = true)
    public BillingView billingView(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        Subscription s = requireCurrent(societyId);
        LocalDateTime now = LocalDateTime.now();
        Long trialLeft = s.getStatus() == SubscriptionStatus.TRIAL && s.getExpiresAt() != null
                ? Math.max(0, ChronoUnit.DAYS.between(now, s.getExpiresAt())) : null;
        LocalDateTime readOnlyFrom = s.isPastDue() && s.getPastDueSince() != null
                ? s.getPastDueSince().plusDays(graceDays) : null;
        List<BillingView.InvoiceView> rows = invoices.findBySociety_IdOrderByIssuedAtDesc(societyId).stream()
                .map(i -> new BillingView.InvoiceView(i.getId(), i.getInvoiceNumber(), i.getAmount(), i.getCurrency(),
                        i.getStatus().getLabel(), i.getIssuedAt(), i.getPaidAt())).toList();
        return new BillingView(s.getId(), s.getPlan(), s.getPlan().getLabel(), s.getStatus(), s.getStatus().getLabel(),
                s.getNextPlan() == null ? null : s.getNextPlan().getLabel(), s.getMonthlyCost(), s.getCurrency(),
                s.getStartedAt(), s.getExpiresAt(), s.getNextBillingAt(), s.getLastPaymentAt(),
                Boolean.TRUE.equals(s.getAutoRenew()), trialLeft, s.isReadOnly(graceDays, now), readOnlyFrom,
                rows, PublicViews.plans());
    }

    /** Mock invoice PDF for download. Scoped by society, so another tenant's invoice id is a 404. */
    @Transactional(readOnly = true)
    public BillingView.InvoiceFile invoicePdf(User actor, Long societyId, Long invoiceId) {
        Tenancy.requireAdminOf(actor, societyId);
        Invoice i = invoices.findByIdAndSociety_Id(invoiceId, societyId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        String when = DateTimeFormatter.ISO_LOCAL_DATE.format(i.getIssuedAt());
        byte[] pdf = MiniPdf.render("SCPL Invoice " + i.getInvoiceNumber(), List.of(
                "Society: " + i.getSociety().getName(),
                "Plan: " + i.getSubscription().getPlan().getLabel(),
                String.format(Locale.ROOT, "Amount: %s %.2f", i.getCurrency(), i.getAmount()),
                "Status: " + i.getStatus().getLabel(),
                "Issued: " + when,
                "",
                "Demo invoice generated by the stub payment gateway. Not a tax document."));
        return new BillingView.InvoiceFile(i.getInvoiceNumber() + ".pdf", pdf);
    }

    // ------------------------------------------------------------------ society admin actions

    /**
     * During a trial this only records the plan to convert to (limits stay at FREE_TRIAL). On an
     * ACTIVE or PAST_DUE subscription it switches immediately, with no proration in this demo.
     * A downgrade is refused while current usage would not fit the smaller plan.
     */
    @Transactional
    public Subscription changePlan(User actor, Long societyId, SubscriptionPlan plan) {
        Tenancy.requireAdminOf(actor, societyId);
        requirePaidPlan(plan);
        Subscription s = requireCurrent(societyId);
        SubscriptionPlan before = s.getNextPlan() != null && s.getStatus() == SubscriptionStatus.TRIAL
                ? s.getNextPlan() : s.getPlan();
        switch (s.getStatus()) {
            case TRIAL -> s.setNextPlan(plan);
            case ACTIVE, PAST_DUE -> {
                if (plan == s.getPlan()) {
                    return s;
                }
                assertFits(societyId, PlanCatalog.of(plan));
                s.setPlan(plan);
                s.setMonthlyCost(PlanCatalog.of(plan).monthlyCost());
                s.setNextPlan(null);
            }
            case CANCELLED -> throw new BusinessRuleException(
                    "This subscription is cancelled. Use Pay now to reactivate it on a plan.");
        }
        subscriptions.save(s);
        audit.record(actor, societyId, AuditActions.PLAN_CHANGED, "SUBSCRIPTION", s.getId(),
                Map.of("plan", before.name()), Map.of("plan", plan.name(), "status", s.getStatus().name()));
        return s;
    }

    /**
     * Charges now and activates (or reactivates) the subscription. plan may be null to use the plan
     * chosen at sign-up or the current one. A declined payment is returned, not thrown, so the
     * PAYMENT_FAILED audit row survives instead of rolling back.
     */
    @Transactional
    public PaymentResult payNow(User actor, Long societyId, SubscriptionPlan planOrNull) {
        Tenancy.requireAdminOf(actor, societyId);
        Subscription s = requireCurrent(societyId);
        SubscriptionPlan target = planOrNull != null ? planOrNull
                : s.getNextPlan() != null ? s.getNextPlan() : s.getPlan();
        requirePaidPlan(target);
        assertFits(societyId, PlanCatalog.of(target));

        LocalDateTime now = LocalDateTime.now();
        PaymentResult result = charge(s, target);
        if (!result.success()) {
            audit.record(actor, societyId, AuditActions.PAYMENT_FAILED, "SUBSCRIPTION", s.getId(), null,
                    Map.of("plan", target.name(), "reason", String.valueOf(result.failureReason())));
            return result;
        }
        applyPayment(s, target, now);
        audit.record(actor, societyId, AuditActions.PAYMENT_SUCCEEDED, "SUBSCRIPTION", s.getId(), null,
                Map.of("plan", target.name(), "reference", result.reference()));
        notifications.notifyAdmins(societyId, null, "Payment received",
                "Your " + target.getLabel() + " subscription is active.", "/society-admin/billing");
        return result;
    }

    /** Turning auto-renew off lets the paid period run out, after which the dunning job cancels. */
    @Transactional
    public Subscription setAutoRenew(User actor, Long societyId, boolean autoRenew) {
        Tenancy.requireAdminOf(actor, societyId);
        Subscription s = requireCurrent(societyId);
        boolean before = Boolean.TRUE.equals(s.getAutoRenew());
        s.setAutoRenew(autoRenew);
        subscriptions.save(s);
        audit.record(actor, societyId, AuditActions.AUTO_RENEW_CHANGED, "SUBSCRIPTION", s.getId(),
                Map.of("autoRenew", before), Map.of("autoRenew", autoRenew));
        return s;
    }

    // ------------------------------------------------------------------ platform admin

    @Transactional(readOnly = true)
    public List<Subscription> listAll(User actor) {
        Tenancy.requirePlatformAdmin(actor);
        return subscriptions.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public Subscription platformSetStatus(User actor, Long subscriptionId, SubscriptionStatus status) {
        Tenancy.requirePlatformAdmin(actor);
        Subscription s = subscriptions.findById(subscriptionId)
                .orElseThrow(() -> new NotFoundException("Subscription not found"));
        SubscriptionStatus before = s.getStatus();
        s.setStatus(status);
        LocalDateTime now = LocalDateTime.now();
        if (status == SubscriptionStatus.PAST_DUE || status == SubscriptionStatus.CANCELLED) {
            if (s.getPastDueSince() == null) {
                s.setPastDueSince(now);
            }
        } else {
            s.setPastDueSince(null);
        }
        subscriptions.save(s);
        audit.record(actor, s.getSocietyId(), AuditActions.SUBSCRIPTION_STATUS_SET, "SUBSCRIPTION", subscriptionId,
                Map.of("status", before.name()), Map.of("status", status.name()));
        return s;
    }

    // ------------------------------------------------------------------ dunning (called by SubscriptionDunningJob)

    /**
     * One pass over every subscription that needs attention. Returns counts for the job log.
     * A single transaction is fine here: the gateway call is the only slow step, and the stub is instant.
     * With a real gateway, move the charge out of the transaction before running this at scale.
     */
    @Transactional
    public Map<String, Integer> runDunning(LocalDateTime now) {
        int converted = 0;
        int renewed = 0;
        int recovered = 0;
        int pastDue = 0;
        int cancelled = 0;

        // 1. Trials that ended: convert to the chosen plan, or fall to PAST_DUE.
        for (Subscription s : subscriptions.findByStatusAndExpiresAtBefore(SubscriptionStatus.TRIAL, now)) {
            SubscriptionPlan target = s.getNextPlan();
            if (target != null && Boolean.TRUE.equals(s.getAutoRenew()) && tryCharge(s, target, now)) {
                converted++;
            } else {
                markPastDue(s, now, "Your free trial has ended.");
                pastDue++;
            }
        }
        // 2. Paid periods that ended: renew, or cancel when the society switched auto-renew off.
        for (Subscription s : subscriptions.findByStatusAndNextBillingAtBefore(SubscriptionStatus.ACTIVE, now)) {
            if (!Boolean.TRUE.equals(s.getAutoRenew())) {
                markCancelled(s, now);
                cancelled++;
            } else if (tryCharge(s, s.getPlan(), now)) {
                renewed++;
            } else {
                markPastDue(s, now, "We could not charge your payment method.");
                pastDue++;
            }
        }
        // 3. Daily retry for past-due paid plans. "before(now)" skips rows marked past due in this run.
        for (Subscription s : subscriptions.findByStatusAndPastDueSinceBefore(SubscriptionStatus.PAST_DUE, now)) {
            if (s.getPlan() != SubscriptionPlan.FREE_TRIAL && Boolean.TRUE.equals(s.getAutoRenew())
                    && tryCharge(s, s.getPlan(), now)) {
                recovered++;
            }
        }
        // 4. Grace period used up: cancel. isReadOnly already blocks writes in real time, so this
        //    only makes the stored status match.
        for (Subscription s : subscriptions.findByStatusAndPastDueSinceBefore(
                SubscriptionStatus.PAST_DUE, now.minusDays(graceDays))) {
            markCancelled(s, now);
            cancelled++;
        }
        Map<String, Integer> report = new LinkedHashMap<>();
        report.put("trialsConverted", converted);
        report.put("renewed", renewed);
        report.put("recovered", recovered);
        report.put("pastDue", pastDue);
        report.put("cancelled", cancelled);
        return report;
    }

    // ------------------------------------------------------------------ internals

    private Subscription requireCurrent(Long societyId) {
        return current(societyId).orElseThrow(() -> new NotFoundException("This society has no subscription"));
    }

    private static void requirePaidPlan(SubscriptionPlan plan) {
        if (plan == null || !PAID_PLANS.contains(plan)) {
            throw new BusinessRuleException("Choose Basic, Standard or Premium");
        }
    }

    private PaymentResult charge(Subscription s, SubscriptionPlan plan) {
        double cost = PlanCatalog.of(plan).monthlyCost();
        try {
            return payments.charge(s.getSocietyId(), s.getStripeCustomerId(), cost, s.getCurrency(),
                    "SCPL " + plan.getLabel() + " subscription");
        } catch (RuntimeException e) {
            // A gateway outage must degrade to "payment failed", not abort the whole dunning run.
            return PaymentResult.failed("Payment gateway error: " + e.getMessage());
        }
    }

    private boolean tryCharge(Subscription s, SubscriptionPlan plan, LocalDateTime now) {
        PaymentResult result = charge(s, plan);
        if (!result.success()) {
            audit.record(null, s.getSocietyId(), AuditActions.PAYMENT_FAILED, "SUBSCRIPTION", s.getId(), null,
                    Map.of("plan", plan.name(), "reason", String.valueOf(result.failureReason())));
            return false;
        }
        applyPayment(s, plan, now);
        audit.record(null, s.getSocietyId(), AuditActions.PAYMENT_SUCCEEDED, "SUBSCRIPTION", s.getId(), null,
                Map.of("plan", plan.name(), "reference", result.reference()));
        return true;
    }

    private void applyPayment(Subscription s, SubscriptionPlan plan, LocalDateTime now) {
        double cost = PlanCatalog.of(plan).monthlyCost();
        s.setPlan(plan);
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setMonthlyCost(cost);
        s.setLastPaymentAt(now);
        s.setNextBillingAt(now.plusMonths(1));
        s.setExpiresAt(s.getNextBillingAt());
        s.setPastDueSince(null);
        s.setNextPlan(null);
        subscriptions.save(s);
        issueInvoice(s, cost, now);
    }

    private void issueInvoice(Subscription s, double amount, LocalDateTime now) {
        Long societyId = s.getSocietyId();
        String stamp = DateTimeFormatter.BASIC_ISO_DATE.format(now);
        int seq = invoices.findBySociety_IdOrderByIssuedAtDesc(societyId).size() + 1;
        String number;
        do {
            number = String.format(Locale.ROOT, "INV-%d-%s-%03d", societyId, stamp, seq++);
        } while (invoices.existsByInvoiceNumber(number));

        Invoice i = new Invoice();
        i.setSociety(s.getSociety());
        i.setSubscription(s);
        i.setInvoiceNumber(number);
        i.setAmount(amount);
        i.setCurrency(s.getCurrency());
        i.setStatus(InvoiceStatus.PAID);
        i.setIssuedAt(now);
        i.setPaidAt(now);
        invoices.save(i);
    }

    private void markPastDue(Subscription s, LocalDateTime now, String reason) {
        s.setStatus(SubscriptionStatus.PAST_DUE);
        if (s.getPastDueSince() == null) {
            s.setPastDueSince(now);
        }
        subscriptions.save(s);
        audit.record(null, s.getSocietyId(), AuditActions.SUBSCRIPTION_PAST_DUE, "SUBSCRIPTION", s.getId(), null,
                Map.of("reason", reason));
        notifications.notifyAdmins(s.getSocietyId(), null, "Payment needed",
                reason + " Pay within " + graceDays + " days to avoid read-only mode. "
                        + "You can still download a backup of your data at any time.", "/society-admin/billing");
    }

    private void markCancelled(Subscription s, LocalDateTime now) {
        s.setStatus(SubscriptionStatus.CANCELLED);
        if (s.getPastDueSince() == null) {
            s.setPastDueSince(now);
        }
        subscriptions.save(s);
        audit.record(null, s.getSocietyId(), AuditActions.SUBSCRIPTION_CANCELLED, "SUBSCRIPTION", s.getId(), null, null);
        notifications.notifyAdmins(s.getSocietyId(), null, "Subscription cancelled",
                "Your society is now read-only. Download a final backup and pay to reactivate.",
                "/society-admin/billing");
    }

    /** A downgrade must not strand data: refuse unless current usage fits the smaller plan. */
    private void assertFits(Long societyId, PlanCatalog.Limits limits) {
        List<Season> open = seasons.findBySociety_IdAndStatusIn(societyId,
                EnumSet.complementOf(EnumSet.of(SeasonStatus.COMPLETED)));
        if (open.size() > limits.maxConcurrentSeasons()) {
            throw new QuotaExceededException("That plan allows " + limits.maxConcurrentSeasons()
                    + " concurrent season(s) and you have " + open.size() + ". Complete a season first.");
        }
        for (Season season : open) {
            if (teams.countBySeason_Id(season.getId()) > limits.maxTeams()) {
                throw new QuotaExceededException("Season '" + season.getName() + "' has more teams than that plan allows ("
                        + limits.maxTeams() + ").");
            }
        }
        if (players.countBySociety_IdAndActiveTrue(societyId) > limits.maxPlayers()) {
            throw new QuotaExceededException("You have more players than that plan allows (" + limits.maxPlayers() + ").");
        }
    }
}