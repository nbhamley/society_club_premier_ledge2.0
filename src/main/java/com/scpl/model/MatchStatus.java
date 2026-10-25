package com.scpl.model;

public enum MatchStatus {
    SCHEDULED("Scheduled"),
    LIVE("Live"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    MatchStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}