package com.enterprisehub.gateway.error;

import com.enterprisehub.gateway.auth.AuthException;
import com.enterprisehub.gateway.apikey.ApiKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiError> handleAuthException(AuthException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(ApiKeyException.class)
    public ResponseEntity<ApiError> handleApiKeyException(ApiKeyException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getMessage()));
    }
}
