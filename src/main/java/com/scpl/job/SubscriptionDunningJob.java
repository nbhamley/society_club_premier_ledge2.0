package com.scpl.job;

import com.scpl.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Daily billing pass (§6): converts ended trials, renews paid plans, retries past-due ones and cancels after the grace
 * period. Read-only mode itself does not wait for this job: ReadOnlyGuard asks SubscriptionService.isReadOnly in real
 * time, so a society is locked on day 31 even if this job is late.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scpl.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SubscriptionDunningJob {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionDunningJob.class);

    private final SubscriptionService subscriptions;

    @Scheduled(cron = "${scpl.subscription.dunning-cron:0 0 3 * * *}")
    public void run() {
        try {
            Map<String, Integer> report = subscriptions.runDunning(LocalDateTime.now());
            log.info("Dunning pass finished: {}", report);
        } catch (RuntimeException e) {
            log.error("Dunning pass failed", e);
        }
    }
}