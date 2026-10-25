package com.scpl.exception;

/** Thrown when an operation that needs a 3-of-N approval is attempted directly. */
public class ApprovalRequiredException extends RuntimeException {

    private final Long approvalId;

    public ApprovalRequiredException(String message, Long approvalId) {
        super(message);
        this.approvalId = approvalId;
    }

    /** The open approval, when one already exists; otherwise null. */
    public Long getApprovalId() {
        return approvalId;
    }
}