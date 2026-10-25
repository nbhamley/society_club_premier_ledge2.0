package com.scpl.util;

import com.scpl.model.SubscriptionPlan;

import java.util.EnumMap;
import java.util.Map;

/**
 * The single place plan prices and limits live (§12: "edit in a constants file, not in code").
 * Changing a price or quota is an edit here and nowhere else.
 */
public final class PlanCatalog {

    public static final int UNLIMITED = Integer.MAX_VALUE;

    public record Limits(double monthlyCost, int maxTeams, int maxPlayers, int maxConcurrentSeasons,
                         boolean vendorModule, boolean aiImport, boolean crossSociety) {
    }

    private static final Map<SubscriptionPlan, Limits> LIMITS = new EnumMap<>(SubscriptionPlan.class);

    static {
        LIMITS.put(SubscriptionPlan.FREE_TRIAL, new Limits(0, 2, 20, 1, false, false, false));
        LIMITS.put(SubscriptionPlan.BASIC, new Limits(499, 4, 60, 1, false, false, false));
        LIMITS.put(SubscriptionPlan.STANDARD, new Limits(999, 8, 150, 2, true, true, false));
        LIMITS.put(SubscriptionPlan.PREMIUM, new Limits(1999, UNLIMITED, UNLIMITED, UNLIMITED, true, true, true));
    }

    private PlanCatalog() {
    }

    public static Limits of(SubscriptionPlan plan) {
        return LIMITS.get(plan);
    }
}