package com.scpl.model;

public enum InvoiceStatus {
    ISSUED("Issued"),
    PAID("Paid"),
    VOID("Void");

    private final String label;

    InvoiceStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}