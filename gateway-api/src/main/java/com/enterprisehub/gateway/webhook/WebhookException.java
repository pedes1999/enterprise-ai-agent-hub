package com.enterprisehub.gateway.webhook;

import com.enterprisehub.gateway.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Same role as AgentException / VendorCredentialException: a readable throw
 * site that GlobalExceptionHandler already knows how to render, via the
 * single DomainException handler.
 *
 * One caution specific to this class: messages on the ingest path are
 * returned to an UNAUTHENTICATED caller, so they must never distinguish
 * "no such endpoint" from "that endpoint belongs to someone else", and must
 * never echo anything derived from the secret.
 */
public class WebhookException extends DomainException {

    public WebhookException(HttpStatus status, String message) {
        super(status, message);
    }
}
