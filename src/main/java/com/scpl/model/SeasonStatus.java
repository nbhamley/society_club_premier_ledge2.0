package com.scpl.model;

public enum SeasonStatus {
    UPCOMING("Upcoming"),
    REGISTRATION_OPEN("Registration Open"),
    AUCTION("Auction"),
    ONGOING("Ongoing"),
    COMPLETED("Completed");

    private final String label;

    SeasonStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}