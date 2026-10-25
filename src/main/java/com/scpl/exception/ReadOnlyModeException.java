package com.scpl.exception;

/**
 * A mutating request arrived for a society that is read-only (PAST_DUE beyond the grace period,
 * CANCELLED or SUSPENDED). GlobalExceptionHandler (Phase 6) maps it to HTTP 403 with the message.
 */
public class ReadOnlyModeException extends RuntimeException {
    public ReadOnlyModeException(String message) {
        super(message);
    }
}