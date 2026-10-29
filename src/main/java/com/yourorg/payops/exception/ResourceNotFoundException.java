package com.yourorg.payops.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String what, Object id) { super(what + " not found: " + id); }
}
