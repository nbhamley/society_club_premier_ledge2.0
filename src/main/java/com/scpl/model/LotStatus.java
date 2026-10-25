package com.scpl.model;

public enum LotStatus {
    PENDING("Pending"),
    LIVE("Live"),
    SOLD("Sold"),
    UNSOLD("Unsold");

    private final String label;

    LotStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}