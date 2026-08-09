package com.enterprisehub.gateway.agent;

import com.enterprisehub.gateway.error.DomainException;
import org.springframework.http.HttpStatus;

public class AgentException extends DomainException {

    public AgentException(HttpStatus status, String message) {
        super(status, message);
    }
}
