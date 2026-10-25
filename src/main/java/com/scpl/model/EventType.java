package com.scpl.model;

public enum EventType {
    MATCH("Match"),
    AUCTION("Auction"),
    TOURNAMENT("Tournament"),
    REGISTRATION_OPEN("Registration Open"),
    AWARDS("Awards"),
    OTHER("Other");

    private final String label;

    EventType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}