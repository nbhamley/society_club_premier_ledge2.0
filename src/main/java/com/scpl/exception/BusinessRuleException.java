package com.scpl.exception;

/** A rule from §6 was violated. The message is safe to show to the user. */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}