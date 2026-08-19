package com.enterprisehub.gateway.cost;

import com.enterprisehub.gateway.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * A tenant has spent its monthly budget. Same shape as AgentException /
 * WebhookException -- GlobalExceptionHandler renders it via the single
 * DomainException handler.
 *
 * Its own type rather than an AgentException with a different status,
 * because callers genuinely need to tell it apart from the concurrency cap:
 * WebhookDeliveryRecorder's transaction rolls back on both, but a 429 there
 * means GitHub's redelivery will eventually succeed, while this one will
 * fail identically until the period rolls over. See
 * TenantBudgetService.requireWithinBudget() for why 402 and not 429.
 */
public class BudgetExceededException extends DomainException {

    public BudgetExceededException(String message) {
        super(HttpStatus.PAYMENT_REQUIRED, message);
    }
}
