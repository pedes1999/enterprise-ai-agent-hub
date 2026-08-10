package com.enterprisehub.gateway.credential;

import com.enterprisehub.gateway.error.DomainException;
import org.springframework.http.HttpStatus;

public class ToolCredentialException extends DomainException {

    public ToolCredentialException(HttpStatus status, String message) {
        super(status, message);
    }
}
