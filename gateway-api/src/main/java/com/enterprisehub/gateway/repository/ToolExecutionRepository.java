package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.ToolExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ToolExecutionRepository extends JpaRepository<ToolExecution, UUID> {
    // RLS on tool_executions is fully closed (FORCE ROW LEVEL SECURITY, no
    // open-read exception like platform_api_keys), so this is just the
    // natural query shape, not a security requirement.
    List<ToolExecution> findByTenantId(UUID tenantId);
}
