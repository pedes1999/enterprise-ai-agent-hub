package com.enterprisehub.gateway.webhook;

import com.enterprisehub.gateway.agent.AgentExecutionService;
import com.enterprisehub.gateway.agent.EnqueueExecutionCommand;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.entity.WebhookDelivery;
import com.enterprisehub.gateway.entity.WebhookEndpoint;
import com.enterprisehub.gateway.repository.WebhookDeliveryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The transactional half of webhook ingest, deliberately a separate bean
 * from {@link WebhookIngestService} for two independent reasons:
 *
 *  1. Spring's @Transactional works through a proxy, so a self-invocation
 *     from an untransacted method on the same bean would silently NOT start
 *     a transaction. This has to be a real cross-bean call to work at all.
 *
 *  2. More subtly, TenantAwareDataSource sets Postgres' app.current_tenant_id
 *     at CONNECTION CHECKOUT. WebhookIngestService looks the endpoint up
 *     before any tenant is known (on a connection with no tenant context)
 *     and only then sets TenantContext. If that lookup and this write shared
 *     one transaction they would share one connection -- the one already
 *     checked out with an empty tenant -- and every RLS-scoped statement here
 *     would fail. The transaction must start after TenantContext is set.
 *
 * Both writes live in ONE transaction on purpose: if the delivery insert
 * trips the UNIQUE (endpoint_id, delivery_id) constraint, the execution it
 * would have queued rolls back with it, so a concurrent duplicate delivery
 * cannot leave a stray run behind.
 */
@Component
public class WebhookDeliveryRecorder {

    private final AgentExecutionService executionService;
    private final WebhookDeliveryRepository deliveryRepository;

    public WebhookDeliveryRecorder(AgentExecutionService executionService, WebhookDeliveryRepository deliveryRepository) {
        this.executionService = executionService;
        this.deliveryRepository = deliveryRepository;
    }

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException if this
     *         delivery id was already recorded for this endpoint -- surfaced
     *         here rather than at commit by the explicit flush, so the caller
     *         can turn it into an idempotent response.
     */
    @Transactional
    public UUID recordAndEnqueue(WebhookEndpoint endpoint, String deliveryId, EnqueueExecutionCommand command) {
        // Ordinary enqueue -- including the per-tenant concurrency cap, which
        // is exactly the protection an unattended trigger needs. A 429 from
        // here propagates out and rolls this transaction back, so no delivery
        // row is written and GitHub's redelivery will genuinely retry rather
        // than being swallowed as a duplicate.
        AgentExecution execution = executionService.enqueue(command);

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setTenantId(endpoint.getTenantId());
        delivery.setEndpointId(endpoint.getId());
        delivery.setDeliveryId(deliveryId);
        delivery.setExecutionId(execution.getId());
        deliveryRepository.saveAndFlush(delivery);

        return execution.getId();
    }
}
