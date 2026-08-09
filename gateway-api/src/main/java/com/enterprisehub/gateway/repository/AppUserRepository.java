package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// Note: no need to add "findByTenantIdAndEmail" style filtering here for
// security purposes -- RLS already guarantees findAll()/findById() etc.
// only ever return rows for the current session's tenant. tenant_id stays
// in the query API purely for cases with multiple candidate rows (e.g.
// email lookup pre-authentication, before TenantContext is even set).
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByTenantIdAndEmail(UUID tenantId, String email);
}
