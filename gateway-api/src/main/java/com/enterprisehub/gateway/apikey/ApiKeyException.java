package com.enterprisehub.gateway.apikey;

import org.springframework.http.HttpStatus;

public class ApiKeyException extends RuntimeException {

    private final HttpStatus status;

    public ApiKeyException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
