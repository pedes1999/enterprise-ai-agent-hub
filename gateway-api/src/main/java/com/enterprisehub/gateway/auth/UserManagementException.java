package com.enterprisehub.gateway.auth;

import com.enterprisehub.gateway.error.DomainException;
import org.springframework.http.HttpStatus;

public class UserManagementException extends DomainException {

    public UserManagementException(HttpStatus status, String message) {
        super(status, message);
    }
}
