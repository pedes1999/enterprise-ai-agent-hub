package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.VendorCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VendorCredentialRepository extends JpaRepository<VendorCredential, UUID> {
    Optional<VendorCredential> findByTenantIdAndProvider(UUID tenantId, String provider);
}
