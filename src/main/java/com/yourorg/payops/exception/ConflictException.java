package com.yourorg.payops.exception;

/** Raised when a request conflicts with an existing business key or resource state. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
