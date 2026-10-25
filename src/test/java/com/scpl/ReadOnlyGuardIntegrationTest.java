package com.scpl;

import com.scpl.model.Subscription;
import com.scpl.model.SubscriptionStatus;
import com.scpl.repo.SubscriptionRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Acceptance 14 (past-due societies become read-only after 30 days but can still pay and read) and the plan gate. */
class ReadOnlyGuardIntegrationTest extends IntegrationTestBase {

    @Autowired
    SubscriptionRepository subscriptions;
    @Autowired
    SocietyRepository societies;
    @Autowired
    SubscriptionService subscriptionService;

    @Test
    void pastDueBeyondTheGracePeriodBlocksWritesButNotReadingOrPaying() throws Exception {
        Long sh = societies.findBySlug("sunrise-heights").orElseThrow().getId();
        Subscription sub = subscriptions.findFirstBySociety_IdOrderByCreatedAtDesc(sh).orElseThrow();
        SubscriptionStatus originalStatus = sub.getStatus();
        LocalDateTime originalPastDue = sub.getPastDueSince();
        try {
            sub.setStatus(SubscriptionStatus.PAST_DUE);
            sub.setPastDueSince(LocalDateTime.now().minusDays(40));
            subscriptions.save(sub);
            assertThat(subscriptionService.isReadOnly(sh)).isTrue();

            mvc.perform(post("/society-admin/sponsors").param("name", "Blocked Sponsor").with(csrf()).with(as("sh.admin1")))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/society/sunrise-heights/register").param("fullName", "x").with(csrf()))
                    .andExpect(status().isForbidden());

            // reading, and the recovery routes, still work
            mvc.perform(get("/society-admin").with(as("sh.admin1"))).andExpect(status().isOk());
            mvc.perform(get("/society-admin/billing").with(as("sh.admin1"))).andExpect(status().isOk());
            mvc.perform(post("/society-admin/billing/auto-renew").param("enabled", "true").with(csrf()).with(as("sh.admin1")))
                    .andExpect(status().is3xxRedirection());
        } finally {
            Subscription restore = subscriptions.findFirstBySociety_IdOrderByCreatedAtDesc(sh).orElseThrow();
            restore.setStatus(originalStatus);
            restore.setPastDueSince(originalPastDue);
            subscriptions.save(restore);
        }
        assertThat(subscriptionService.isReadOnly(sh)).isFalse();
    }

    @Test
    void recentlyPastDueSocietiesStillWrite() throws Exception {
        Long sh = societies.findBySlug("sunrise-heights").orElseThrow().getId();
        Subscription sub = subscriptions.findFirstBySociety_IdOrderByCreatedAtDesc(sh).orElseThrow();
        SubscriptionStatus originalStatus = sub.getStatus();
        LocalDateTime originalPastDue = sub.getPastDueSince();
        try {
            sub.setStatus(SubscriptionStatus.PAST_DUE);
            sub.setPastDueSince(LocalDateTime.now().minusDays(5));
            subscriptions.save(sub);
            assertThat(subscriptionService.isReadOnly(sh)).isFalse();
        } finally {
            Subscription restore = subscriptions.findFirstBySociety_IdOrderByCreatedAtDesc(sh).orElseThrow();
            restore.setStatus(originalStatus);
            restore.setPastDueSince(originalPastDue);
            subscriptions.save(restore);
        }
    }

    @Test
    void theVendorModuleIsGatedByPlan() throws Exception {
        // Sunrise Heights is on the free trial: the vendor module starts at Standard.
        mvc.perform(get("/society-admin/vendors").with(as("sh.admin1"))).andExpect(status().isForbidden());
        mvc.perform(get("/society-admin/vendors").with(as("gv.admin1"))).andExpect(status().isOk());
    }
}