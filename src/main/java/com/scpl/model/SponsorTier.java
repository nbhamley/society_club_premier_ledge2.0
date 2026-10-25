package com.scpl.model;

public enum SponsorTier {
    TITLE("Title Sponsor"),
    GOLD("Gold"),
    SILVER("Silver"),
    BRONZE("Bronze");

    private final String label;

    SponsorTier(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}