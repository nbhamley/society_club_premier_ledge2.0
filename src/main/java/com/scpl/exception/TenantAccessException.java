package com.scpl.exception;

/** Cross-tenant or wrong-role access. GlobalExceptionHandler (Phase 6) maps it to HTTP 403. */
public class TenantAccessException extends RuntimeException {
    public TenantAccessException(String message) {
        super(message);
    }
}