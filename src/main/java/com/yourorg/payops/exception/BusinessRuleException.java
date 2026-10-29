package com.yourorg.payops.exception;

/** Thrown by controllers when a business rule is broken (e.g. unbalanced journal). */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) { super(message); }
}
