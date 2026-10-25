package com.scpl.job;

import com.scpl.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Keeps the bell menu table small: only notifications that were already read are deleted. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scpl.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationHousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationHousekeepingJob.class);

    private final NotificationService notifications;

    @Value("${scpl.notifications.retention-days:90}")
    private int retentionDays;

    @Scheduled(cron = "0 45 3 * * *")
    public void run() {
        try {
            int removed = notifications.purgeRead(LocalDateTime.now().minusDays(retentionDays));
            log.info("Notification housekeeping removed {} read notification(s)", removed);
        } catch (RuntimeException e) {
            log.error("Notification housekeeping failed", e);
        }
    }
}