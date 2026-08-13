package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.AgentExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, UUID> {

    Optional<AgentExecution> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Backs the per-tenant concurrency cap -- see AgentExecutionService.enqueue(). Normal RLS applies (no worker-sentinel involved). */
    long countByTenantIdAndStatusIn(UUID tenantId, Collection<String> statuses);

    /** Backs GET /agents/executions (no status filter). */
    Page<AgentExecution> findByTenantId(UUID tenantId, Pageable pageable);

    /** Backs GET /agents/executions?status=... */
    Page<AgentExecution> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);

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

    /**
     * Backs GET /agents/executions/token-usage-stats -- count/min/avg/max
     * over this tenant's past executions of one agent that actually
     * recorded token usage (see AgentExecution.totalTokens' javadoc).
     * Always returns exactly one row, even when zero rows match: SQL
     * aggregates over an empty set yield count=0 and null for the rest,
     * never no row at all -- AgentExecutionService.getTokenUsageStats()
     * relies on that to represent "no data yet" instead of an empty Optional.
     */
    @Query("select count(e), min(e.totalTokens), avg(e.totalTokens), max(e.totalTokens) "
            + "from AgentExecution e where e.tenantId = :tenantId and e.agentType = :agentSlug and e.totalTokens is not null")
    Object[] tokenUsageStatsRaw(UUID tenantId, String agentSlug);
}
