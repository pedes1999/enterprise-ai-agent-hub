package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.PlatformApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformApiKeyRepository extends JpaRepository<PlatformApiKey, UUID> {
    // Looked up pre-authentication (that's the whole point of an API key),
    // so this runs with no TenantContext set -- fine, since key_hash is
    // globally unique and this is the ONE query allowed to cross tenants
    // in order to figure out which tenant the key belongs to.
    Optional<PlatformApiKey> findByKeyHash(String keyHash);

    // IMPORTANT: unlike every other tenant-scoped table, the RLS SELECT
    // policy on platform_api_keys is deliberately wide open (see
    // V1__init_schema.sql) to make findByKeyHash above possible. That means
    // the database is NOT enforcing tenant isolation on reads of this table
    // -- these two methods are the app-level substitute and MUST be used
    // for anything reachable by an authenticated tenant caller. Never expose
    // findAll()/findById() for this entity to a controller.
    List<PlatformApiKey> findByTenantId(UUID tenantId);

    Optional<PlatformApiKey> findByIdAndTenantId(UUID id, UUID tenantId);
}
