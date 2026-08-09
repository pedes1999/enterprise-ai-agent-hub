package com.enterprisehub.gateway.credential;

import com.enterprisehub.gateway.error.DomainException;
import org.springframework.http.HttpStatus;

public class VendorCredentialException extends DomainException {

    public VendorCredentialException(HttpStatus status, String message) {
        super(status, message);
    }
}
