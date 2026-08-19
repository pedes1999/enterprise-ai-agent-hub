package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    /**
     * The pre-authentication lookup: the incoming request knows only the id
     * from its URL, and this is the query that discovers which tenant it
     * belongs to. Runs with NO tenant context set, which V34's
     * webhook_endpoints_lookup_by_id policy (FOR SELECT USING (true))
     * deliberately permits -- see that migration for the reasoning.
     */
    Optional<WebhookEndpoint> findByIdAndActiveTrue(UUID id);

    /**
     * Every tenant-facing read MUST go through this, never findAll() or a
     * bare findById(): the open SELECT policy above means the database is
     * not filtering this table by tenant for us the way it does everywhere
     * else, so the tenant predicate here is load-bearing security rather
     * than just the natural query shape. Same discipline
     * PlatformApiKeyRepository needs for the same reason.
     */
    List<WebhookEndpoint> findByTenantIdAndActiveTrueOrderByCreatedAtDesc(UUID tenantId);

    /** Tenant-scoped by hand for the reason above -- used by the ADMIN deactivate path. */
    Optional<WebhookEndpoint> findByIdAndTenantId(UUID id, UUID tenantId);
}
