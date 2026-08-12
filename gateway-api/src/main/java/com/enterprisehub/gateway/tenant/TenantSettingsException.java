package com.enterprisehub.gateway.tenant;

import com.enterprisehub.gateway.error.DomainException;
import org.springframework.http.HttpStatus;

public class TenantSettingsException extends DomainException {

    public TenantSettingsException(HttpStatus status, String message) {
        super(status, message);
    }
}
