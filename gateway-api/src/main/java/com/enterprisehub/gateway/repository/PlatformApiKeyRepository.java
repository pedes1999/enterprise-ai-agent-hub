package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.PlatformApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlatformApiKeyRepository extends JpaRepository<PlatformApiKey, UUID> {
    // Looked up pre-authentication (that's the whole point of an API key),
    // so this runs with no TenantContext set -- fine, since key_hash is
    // globally unique and this is the ONE query allowed to cross tenants
    // in order to figure out which tenant the key belongs to.
    Optional<PlatformApiKey> findByKeyHash(String keyHash);
}
