package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.ToolCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToolCredentialRepository extends JpaRepository<ToolCredential, UUID> {
    Optional<ToolCredential> findByTenantIdAndCredentialKind(UUID tenantId, String credentialKind);

    List<ToolCredential> findByTenantId(UUID tenantId);
}
