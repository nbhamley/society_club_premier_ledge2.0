package com.scpl.dto;

import com.scpl.model.SubscriptionPlan;
import com.scpl.model.SubscriptionStatus;

import java.time.LocalDateTime;
import java.util.List;

/** Everything the billing page shows, computed server-side so the template does no date maths. */
public record BillingView(Long subscriptionId, SubscriptionPlan plan, String planLabel, SubscriptionStatus status,
                          String statusLabel, String nextPlanLabel, double monthlyCost, String currency,
                          LocalDateTime startedAt, LocalDateTime expiresAt, LocalDateTime nextBillingAt,
                          LocalDateTime lastPaymentAt, boolean autoRenew, Long trialDaysLeft, boolean readOnly,
                          LocalDateTime readOnlyFrom, List<InvoiceView> invoices,
                          List<PublicViews.PlanView> plans) {

    public record InvoiceView(Long id, String number, double amount, String currency, String statusLabel,
                              LocalDateTime issuedAt, LocalDateTime paidAt) {
    }

    /** A rendered mock invoice, ready to stream as application/pdf. */
    public record InvoiceFile(String fileName, byte[] bytes) {
    }
}