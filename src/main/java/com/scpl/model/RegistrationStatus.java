package com.scpl.model;

public enum RegistrationStatus {
    PENDING("Pending"),
    /** An AdminApproval has been raised and is collecting votes. */
    AWAITING_APPROVAL("Awaiting Admin Approval"),
    APPROVED("Approved"),
    REJECTED("Rejected");

    private final String label;

    RegistrationStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}