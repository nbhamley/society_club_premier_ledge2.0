package com.scpl.model;

/** DRAFT cannot become ACTIVE until the society has the minimum number of admins (§6). */
public enum SocietyStatus {
    DRAFT("Draft"),
    ACTIVE("Active"),
    SUSPENDED("Suspended");

    private final String label;

    SocietyStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}