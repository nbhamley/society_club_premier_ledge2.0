package com.scpl.exception;

/** Also thrown for a wrong-tenant id, so a probe cannot tell "exists elsewhere" from "does not exist". */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}