package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /**
     * Reads the delivery a duplicate collides with, so a redelivery can be
     * answered with the id of the execution the FIRST delivery created
     * instead of a bare "already seen". Only ever called after
     * WebhookIngestService has set TenantContext, so the ordinary
     * tenant-scoped RLS policy applies here (unlike webhook_endpoints).
     */
    Optional<WebhookDelivery> findByEndpointIdAndDeliveryId(UUID endpointId, String deliveryId);
}
