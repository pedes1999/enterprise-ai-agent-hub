package com.enterprisehub.gateway.error;

import org.springframework.http.HttpStatus;

/**
 * Base for exceptions a controller should surface as a specific HTTP status
 * with a client-safe message. GlobalExceptionHandler has a single handler
 * for this type -- subclasses exist only to keep call sites readable
 * (throw new UserManagementException(...) reads better than a generic
 * DomainException everywhere), not because they carry different behavior.
 */
public class DomainException extends RuntimeException {

    private final HttpStatus status;

    public DomainException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
