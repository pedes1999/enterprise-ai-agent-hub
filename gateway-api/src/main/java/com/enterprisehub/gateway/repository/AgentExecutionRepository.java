package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.AgentExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, UUID> {

    Optional<AgentExecution> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Backs the per-tenant concurrency cap -- see AgentExecutionService.enqueue(). Normal RLS applies (no worker-sentinel involved). */
    long countByTenantIdAndStatusIn(UUID tenantId, Collection<String> statuses);

    /**
     * Atomically claims the oldest still-QUEUED job across every tenant.
     * FOR UPDATE SKIP LOCKED means concurrent callers (multiple worker
     * threads, multiple app instances) never block on or double-claim the
     * same row -- each one just skips rows another caller already has
     * locked and grabs the next available one. Must be called from within
     * a short-lived @Transactional method that also flips the row to
     * RUNNING before committing (see AgentExecutionService.claimNext) --
     * the row lock is only held for that transaction's duration, not for
     * the actual (potentially slow) agent run afterwards.
     *
     * Relies on TenantContext being set to TenantContext.SYSTEM_WORKER_TENANT_ID
     * for this call specifically -- see V5__agent_execution_queue.sql's RLS
     * policy for why this query can see rows belonging to every tenant.
     */
    @Query(value = "SELECT * FROM agent_executions WHERE status = 'QUEUED' "
            + "ORDER BY created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<AgentExecution> claimNextQueued();
}
