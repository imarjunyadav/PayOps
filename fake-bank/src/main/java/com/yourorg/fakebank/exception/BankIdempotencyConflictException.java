package com.yourorg.fakebank.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class BankIdempotencyConflictException extends RuntimeException {
    public BankIdempotencyConflictException(String message) {
        super(message);
    }
}
