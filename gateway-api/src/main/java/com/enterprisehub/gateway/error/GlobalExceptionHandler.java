package com.enterprisehub.gateway.error;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomainException(DomainException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getMessage()));
    }

    // POST /knowledge-sources/{id}/documents is the first upload endpoint in
    // the app (see application.yml's spring.servlet.multipart limits) --
    // without this, an oversized file produces Spring's default non-JSON
    // error page instead of the same ApiError shape every other failure uses.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(new ApiError("Uploaded file is too large"));
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
