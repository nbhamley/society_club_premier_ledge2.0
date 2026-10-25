package com.scpl.dto;

import com.scpl.model.EventScope;
import com.scpl.model.EventType;
import com.scpl.model.SocietyStatus;
import com.scpl.model.SubscriptionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Platform-admin read models (§2, §14). Counts, plan and billing state, and audit rows only: there is deliberately
 * no field anywhere in here that can hold player PII.
 */
public final class PlatformViews {

    private PlatformViews() {
    }

    public record Stats(long societies, long activeSocieties, long draftSocieties, long suspendedSocieties,
                        long teams, long players, long matches, long trial, long active, long pastDue, long cancelled,
                        double monthlyRecurringRevenue, long openApprovals, long upcomingEvents) {
    }

    public record SocietyRow(Long id, String name, String slug, String city, SocietyStatus status, String statusLabel,
                             int adminCount, boolean canActivate, String planLabel, String subscriptionStatusLabel,
                             long teams, long players, LocalDateTime createdAt) {
    }

    public record SubscriptionRow(Long id, Long societyId, String societyName, String planLabel,
                                  SubscriptionStatus status, String statusLabel, double monthlyCost, String currency,
                                  LocalDateTime startedAt, LocalDateTime expiresAt, LocalDateTime nextBillingAt,
                                  LocalDateTime lastPaymentAt, LocalDateTime pastDueSince, boolean autoRenew) {
    }

    public record EventRow(Long id, String title, String description, LocalDateTime eventDate, EventScope scope,
                           String scopeLabel, EventType type, String typeLabel, Long societyId, String societyName,
                           String thumbnailUrl, String externalLink, boolean published) {
    }

    public record SocietyOption(Long id, String name) {
    }

    public record AuditRow(LocalDateTime timestamp, String user, Long societyId, String societyName, String action,
                           String entityType, Long entityId, String detail) {
    }

    public record AuditPageView(List<AuditRow> rows, int page, int pages, Long societyId) {
    }

    public record DashboardView(Stats stats, List<SocietyRow> awaitingActivation, List<SubscriptionRow> needAttention,
                                List<AuditRow> recent, boolean crossSocietyEnabled) {
    }

    public record Options(EventScope[] eventScopes, EventType[] eventTypes, SubscriptionStatus[] subscriptionStatuses) {
        public static Options all() {
            return new Options(EventScope.values(), EventType.values(), SubscriptionStatus.values());
        }
    }
}