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

    /**
     * Backs GET /agents/executions/{id}/tool-executions -- executionId is a
     * plain string (not an FK, see this entity's javadoc / V3), matching
     * AgentExecution.id.toString(). Ordered oldest-first, i.e. the actual
     * order tools ran in during that execution.
     */
    List<ToolExecution> findByTenantIdAndExecutionIdOrderByCreatedAtAsc(UUID tenantId, String executionId);
}
