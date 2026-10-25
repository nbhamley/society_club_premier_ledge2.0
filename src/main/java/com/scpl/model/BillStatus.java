package com.scpl.model;

public enum BillStatus {
    PENDING("Pending"),
    PAID("Paid"),
    DISPUTED("Disputed");

    private final String label;

    BillStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}