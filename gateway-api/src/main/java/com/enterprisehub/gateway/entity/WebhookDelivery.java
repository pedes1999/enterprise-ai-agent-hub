package com.enterprisehub.gateway.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One accepted delivery. Exists for exactly one reason: idempotency.
 *
 * GitHub retries a failed delivery on its own schedule and lets a repo admin
 * redeliver any past one from the UI, reusing the same X-GitHub-Delivery id
 * each time. Without the UNIQUE (endpoint_id, delivery_id) constraint behind
 * this entity (see V34), one pull request could queue -- and bill -- the same
 * agent run repeatedly. GitHub sends no timestamp header, so unlike a
 * Stripe-style signed-timestamp window this uniqueness IS the replay defence.
 *
 * executionId is nullable only because the FK is ON DELETE SET NULL: a
 * delivery row outlives the execution it created if that execution is ever
 * purged, and the delivery record is still the useful history.
 */
@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
public class WebhookDelivery {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    /** GitHub's X-GitHub-Delivery header -- stable across retries, which is what makes it an idempotency key. */
    @Column(name = "delivery_id", nullable = false)
    private String deliveryId;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();
}
