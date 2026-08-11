package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The "durable job orchestration" piece: a poll loop claiming one QUEUED
 * agent_executions row at a time and running it via AgentPromptRunner.
 * DB-backed (SELECT ... FOR UPDATE SKIP LOCKED in
 * AgentExecutionRepository.claimNextQueued, via AgentExecutionService),
 * not a message broker -- see V5__agent_execution_queue.sql for why that's
 * the right tradeoff at this stage, and how a future move to a real broker
 * would only replace this class, not AgentExecutionService/AgentPromptRunner.
 *
 * fixedDelay (not fixedRate) is deliberate: Spring won't schedule the next
 * poll until the current one -- including a full agent run, which can take
 * anywhere from seconds to a couple of minutes with sandboxed tools -- has
 * finished. That means only one job runs at a time per app instance today;
 * running N in parallel is a matter of increasing the scheduler's pool
 * size later, not a redesign.
 *
 * Disabled entirely (no bean at all, not just an inert one) when
 * app.job-worker.enabled=false -- set in application-test.yml so
 * @SpringBootTest integration tests don't have a background poller racing
 * against whatever agent_executions rows a test itself is asserting on.
 */
@Component
@ConditionalOnProperty(prefix = "app.job-worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentJobWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentJobWorker.class);

    private final AgentExecutionService executionService;
    private final AgentPromptRunner agentPromptRunner;

    public AgentJobWorker(AgentExecutionService executionService, AgentPromptRunner agentPromptRunner) {
        this.executionService = executionService;
        this.agentPromptRunner = agentPromptRunner;
    }

    @Scheduled(fixedDelayString = "${app.job-worker.poll-interval-ms:2000}")
    public void pollAndProcessOne() {
        AgentExecution job = claimNext();
        if (job == null) {
            return;
        }
        runClaimedJob(job);
    }

    private AgentExecution claimNext() {
        // The only place in the whole codebase this sentinel is set -- see
        // its javadoc. Cleared in the finally block regardless of outcome,
        // same discipline as TenantResolvingFilter for a real request.
        TenantContext.set(TenantContext.SYSTEM_WORKER_TENANT_ID);
        try {
            return executionService.claimNext().orElse(null);
        } finally {
            TenantContext.clear();
        }
    }

    private void runClaimedJob(AgentExecution job) {
        // Switch from the worker sentinel to the job's REAL tenant before
        // touching anything else -- credential decryption, the LLM call,
        // sandboxed tool execution, and audit logging must all see this
        // tenant's own RLS-scoped data, not the sentinel's "see everything"
        // view, which only ever applies to the claim query above.
        TenantContext.set(job.getTenantId().toString());
        try {
            ToolCallingChatEngine.ToolChatResult result = agentPromptRunner.run(
                    job.getTenantId(), job.getId().toString(), job.getAgentType(), job.getPrompt(),
                    job.getRepositoryUrl(), executionService.deserializeInputParameters(job));
            executionService.complete(job.getId(), result.reply(), result.toolWasUsed());
        } catch (RuntimeException e) {
            log.warn("Agent execution {} (tenant {}) failed", job.getId(), job.getTenantId(), e);
            executionService.fail(job.getId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
