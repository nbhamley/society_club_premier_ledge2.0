package com.scpl.model;

public enum SubscriptionStatus {
    TRIAL("Trial"),
    ACTIVE("Active"),
    PAST_DUE("Past Due"),
    CANCELLED("Cancelled");

    private final String label;

    SubscriptionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}