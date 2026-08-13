package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.VendorCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorCredentialRepository extends JpaRepository<VendorCredential, UUID> {

    /** A specific user's own credential for one provider -- the only shape execution-time resolution ever uses now (see AgentPromptRunner.resolveApiKey()). */
    Optional<VendorCredential> findByTenantIdAndUserIdAndProvider(UUID tenantId, UUID userId, String provider);

    /** A user's own credentials list -- backs "my vendor credentials" (GET /vendor-credentials). */
    List<VendorCredential> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    // RLS on vendor_credentials is fully closed (unlike platform_api_keys),
    // so this is just the natural query shape, not a security requirement.
    // Only ADMIN-facing cross-user queries (the team view) use this now --
    // every execution-time / self-service lookup is scoped by userId too.
    List<VendorCredential> findByTenantId(UUID tenantId);

    /** Tenant-wide "does ANYONE have this provider connected" check -- backs TenantSettingsService's availableProviders(), which is a policy-setting sanity check, not tied to any one user's key. */
    boolean existsByTenantIdAndProviderAndActiveTrue(UUID tenantId, String provider);
}
