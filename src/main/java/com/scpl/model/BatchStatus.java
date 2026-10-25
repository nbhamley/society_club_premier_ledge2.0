package com.scpl.model;

public enum BatchStatus {
    UPLOADED("Uploaded"),
    SEGREGATING("Segregating"),
    READY_FOR_REVIEW("Ready for Review"),
    APPROVED("Approved"),
    PARTIALLY_APPROVED("Partially Approved"),
    REJECTED("Rejected"),
    FAILED("Failed");

    private final String label;

    BatchStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}