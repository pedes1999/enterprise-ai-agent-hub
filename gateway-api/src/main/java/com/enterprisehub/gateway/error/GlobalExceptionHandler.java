package com.enterprisehub.gateway.error;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomainException(DomainException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getMessage()));
    }

    // Thrown by @PreAuthorize when an authenticated caller's role doesn't
    // satisfy the check (e.g. a DEVELOPER hitting an ADMIN-only endpoint).
    // Without this, Spring's default AccessDeniedHandler produces a bare
    // 403 with no JSON body, inconsistent with every other error response.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(new ApiError("Access denied"));
    }
}
