package com.scpl.exception;

/** The society's subscription plan does not allow the requested addition. */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}