package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Note: no need to add "findByTenantIdAndEmail" style filtering here for
// security purposes -- RLS already guarantees findAll()/findById() etc.
// only ever return rows for the current session's tenant. tenant_id stays
// in the query API purely for cases with multiple candidate rows (e.g.
// email lookup pre-authentication, before TenantContext is even set).
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByTenantIdAndEmail(UUID tenantId, String email);

    // Unlike platform_api_keys, app_users' RLS SELECT policy is NOT open --
    // findAll()/findById() are already tenant-scoped by the database itself.
    // findByTenantId below is just the natural query shape for "list my
    // tenant's users", not a security requirement the way it is for keys.
    List<AppUser> findByTenantId(UUID tenantId);

    long countByTenantIdAndRole(UUID tenantId, String role);
}
