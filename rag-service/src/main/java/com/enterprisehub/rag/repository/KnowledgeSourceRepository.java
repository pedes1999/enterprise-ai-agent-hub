package com.enterprisehub.rag.repository;

import com.enterprisehub.rag.entity.KnowledgeSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeSourceRepository extends JpaRepository<KnowledgeSource, UUID> {

    List<KnowledgeSource> findByTenantId(UUID tenantId);

    /** Explicit tenantId scoping alongside id, matching the rest of the codebase's belt-and-suspenders style on top of RLS (see VendorCredentialRepository). */
    Optional<KnowledgeSource> findByIdAndTenantId(UUID id, UUID tenantId);
}
