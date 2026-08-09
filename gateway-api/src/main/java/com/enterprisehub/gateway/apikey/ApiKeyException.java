package com.enterprisehub.gateway.apikey;

import com.enterprisehub.gateway.error.DomainException;
import org.springframework.http.HttpStatus;

public class ApiKeyException extends DomainException {

    public ApiKeyException(HttpStatus status, String message) {
        super(status, message);
    }
}
