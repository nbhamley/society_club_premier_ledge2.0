package com.scpl.model;

public enum TournamentStatus {
    PROPOSED("Proposed"),
    APPROVING("Approving"),
    APPROVED("Approved"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    TournamentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}