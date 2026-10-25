package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class Subscription extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionPlan plan = SubscriptionPlan.FREE_TRIAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status = SubscriptionStatus.TRIAL;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "monthly_cost", nullable = false)
    private Double monthlyCost = 0.0;

    @Column(nullable = false)
    private String currency = "INR";

    @Column(name = "auto_renew", nullable = false)
    private Boolean autoRenew = Boolean.TRUE;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "last_payment_at")
    private LocalDateTime lastPaymentAt;

    @Column(name = "next_billing_at")
    private LocalDateTime nextBillingAt;

    /** Set when the status flips to PAST_DUE; the dunning job measures the 30-day cutoff from here. */
    @Column(name = "past_due_since")
    private LocalDateTime pastDueSince;

    /** Plan picked at sign-up. It becomes the real plan when the trial converts to a paid subscription. */
    @Enumerated(EnumType.STRING)
    @Column(name = "next_plan")
    private SubscriptionPlan nextPlan;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    public boolean isPastDue() {
        return status == SubscriptionStatus.PAST_DUE;
    }

    /** Read-only once PAST_DUE for longer than graceDays, or when cancelled. */
    public boolean isReadOnly(int graceDays, LocalDateTime now) {
        if (status == SubscriptionStatus.CANCELLED) {
            return true;
        }
        return status == SubscriptionStatus.PAST_DUE
                && pastDueSince != null
                && pastDueSince.plusDays(graceDays).isBefore(now);
    }
}