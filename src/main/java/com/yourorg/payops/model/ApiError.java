package com.yourorg.payops.model;

import java.time.LocalDateTime;

/** Plain (non-DB) error body returned by GlobalExceptionHandler. */
public class ApiError {
    private final int status;
    private final String message;
    private final LocalDateTime timestamp = LocalDateTime.now();
    public ApiError(int status, String message) { this.status = status; this.message = message; }
    public int getStatus() { return status; }
    public String getMessage() { return message; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
