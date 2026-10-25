package com.scpl.model;

/** Prices and limits live in a constants/config class (Phase 4), never in this enum. */
public enum SubscriptionPlan {
    FREE_TRIAL("Free Trial"),
    BASIC("Basic"),
    STANDARD("Standard"),
    PREMIUM("Premium");

    private final String label;

    SubscriptionPlan(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}