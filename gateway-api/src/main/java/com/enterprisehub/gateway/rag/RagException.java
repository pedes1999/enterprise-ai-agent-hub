package com.enterprisehub.gateway.rag;

import com.enterprisehub.gateway.error.DomainException;
import org.springframework.http.HttpStatus;

public class RagException extends DomainException {

    public RagException(HttpStatus status, String message) {
        super(status, message);
    }
}
