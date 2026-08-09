package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.VendorCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorCredentialRepository extends JpaRepository<VendorCredential, UUID> {
    Optional<VendorCredential> findByTenantIdAndProvider(UUID tenantId, String provider);

    // RLS on vendor_credentials is fully closed (unlike platform_api_keys),
    // so this is just the natural query shape, not a security requirement.
    List<VendorCredential> findByTenantId(UUID tenantId);
}
