package com.scpl.model;

public enum Vote {
    APPROVE("Approve"),
    REJECT("Reject");

    private final String label;

    Vote(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}