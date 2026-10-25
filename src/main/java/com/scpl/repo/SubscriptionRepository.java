package com.scpl.repo;

import com.scpl.model.Subscription;
import com.scpl.model.SubscriptionStatus;
import org.springframework.data.jpa.repository.EntityGraph;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends SocietyScopedRepository<Subscription, Long> {

    /** The society's current subscription is its newest row. ReadOnlyGuard calls this on every mutating request. */
    Optional<Subscription> findFirstBySociety_IdOrderByCreatedAtDesc(Long societyId);

    List<Subscription> findBySociety_IdOrderByCreatedAtDesc(Long societyId);

    @EntityGraph(attributePaths = "society")
    List<Subscription> findAllByOrderByCreatedAtDesc();

    long countByStatus(SubscriptionStatus status);

    // ---- SubscriptionDunningJob inputs ----

    /** Trials whose 14 days are up. */
    @EntityGraph(attributePaths = "society")
    List<Subscription> findByStatusAndExpiresAtBefore(SubscriptionStatus status, LocalDateTime cutoff);

    /** Active plans whose billing date has passed and need a charge attempt. */
    @EntityGraph(attributePaths = "society")
    List<Subscription> findByStatusAndNextBillingAtBefore(SubscriptionStatus status, LocalDateTime cutoff);

    /** PAST_DUE longer than the grace period: these societies flip to read-only. */
    @EntityGraph(attributePaths = "society")
    List<Subscription> findByStatusAndPastDueSinceBefore(SubscriptionStatus status, LocalDateTime cutoff);
}