package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import com.enterprisehub.gateway.tenant.TenantLlmProviderResolver;
import com.enterprisehub.gateway.credential.LocalModelHint;

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

    /**
     * Every log line emitted anywhere beneath a claimed job carries this,
     * so a whole run -- including failures raised deep inside
     * AgentPromptRunner or a tool -- can be pulled out of the log by the
     * same execution id the API and UI already show the user.
     */
    private static final String MDC_EXECUTION_ID = "executionId";

    private final AgentExecutionService executionService;
    private final AgentPromptRunner agentPromptRunner;
    private final ExecutionHeartbeatMonitor heartbeatMonitor;
    private final LocalModelHint localModelHint;
    private final TenantLlmProviderResolver tenantLlmProviderResolver;

    public AgentJobWorker(AgentExecutionService executionService, AgentPromptRunner agentPromptRunner,
                           ExecutionHeartbeatMonitor heartbeatMonitor,
                           LocalModelHint localModelHint,
                           TenantLlmProviderResolver tenantLlmProviderResolver) {
        this.executionService = executionService;
        this.agentPromptRunner = agentPromptRunner;
        this.heartbeatMonitor = heartbeatMonitor;
        this.localModelHint = localModelHint;
        this.tenantLlmProviderResolver = tenantLlmProviderResolver;
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
        // From here until the finally block this row is this instance's
        // responsibility: the monitor stamps it on a timer so no other
        // instance reaps it as abandoned. Registered BEFORE any work starts
        // and removed in the finally regardless of outcome -- an id left
        // behind here would keep being stamped forever, which is precisely
        // the "RUNNING but nobody's actually running it" state this whole
        // mechanism exists to prevent.
        heartbeatMonitor.track(job.getId());
        MDC.put(MDC_EXECUTION_ID, job.getId().toString());
        long startedAtNanos = System.nanoTime();
        log.info("Starting agent execution: agent={} tenant={} repository={}",
                job.getAgentType(), job.getTenantId(), job.getRepositoryUrl());
        try {
            // Stamp the model BEFORE the run, not after: this is what makes
            // the run costable (pricing is per model -- see V35), and doing
            // it up front means a crash, a reap, or a cancellation mid-run
            // still leaves a row that can be attributed rather than an
            // unpriceable orphan.
            executionService.recordResolvedModel(job.getId(),
                    agentPromptRunner.resolveModelName(job.getTenantId(), job.getAgentType()));

            ToolCallingChatEngine.ToolChatResult result = agentPromptRunner.run(
                    AgentRunRequest.of(job.getTenantId(), job.getTriggeredBy(), job.getId().toString(), job.getAgentType())
                            .prompt(job.getPrompt())
                            .repository(job.getRepositoryUrl(), job.getRepositoryBranch())
                            .inputParameters(executionService.deserializeInputParameters(job))
                            .maxTokensOverride(job.getMaxTokensOverride())
                            .cancellationCheck(() -> executionService.isCancellationRequested(job.getId()))
                            .build());
            if (result.cancelled()) {
                // Checked before incomplete() -- a cancel is what the user
                // asked for, not a failure, and gets its own terminal status
                // (see AgentExecutionService.cancel()).
                log.info("Agent execution cancelled after {}", elapsed(startedAtNanos));
                executionService.cancel(job.getId(), result.inputTokens(), result.outputTokens(), result.totalTokens());
            } else if (result.incomplete()) {
                log.warn("Agent execution did not finish after {}: {}", elapsed(startedAtNanos), result.incompleteReason());
                executionService.fail(job.getId(), result.incompleteReason(),
                        result.inputTokens(), result.outputTokens(), result.totalTokens());
            } else if (result.toolWasUsed() && (result.reply() == null || result.reply().isBlank())) {
                // A genuine final answer (incomplete==false) that is still blank
                // despite having used tools is its own kind of failure -- most
                // often every tool call the model tried failed for real (e.g.
                // the sandbox sidecar was unreachable) and it gave up without
                // ever writing a summary. The individual tool failures are
                // already recorded in tool_executions (see ToolExecutionListener),
                // but nothing previously made the execution itself reflect that;
                // it silently reported SUCCEEDED with nothing to show for it.
                log.warn("Agent execution used tools but produced no final summary after {}", elapsed(startedAtNanos));
                executionService.fail(job.getId(),
                        "Agent used tools but produced no final summary -- check the tool-call trace for repeated failures.",
                        result.inputTokens(), result.outputTokens(), result.totalTokens());
            } else {
                log.info("Agent execution succeeded in {}: toolWasUsed={} totalTokens={}",
                        elapsed(startedAtNanos), result.toolWasUsed(), result.totalTokens());
                executionService.complete(job.getId(), result.reply(), result.toolWasUsed(),
                        result.inputTokens(), result.outputTokens(), result.totalTokens());
            }
        } catch (ToolCallingChatEngine.PartialUsageException e) {
            // A provider call failed mid-run (rate limit, insufficient API
            // credit, network error) after earlier rounds already succeeded
            // and were genuinely billed -- see PartialUsageException's
            // javadoc. Recording that spend here is the whole reason this
            // catch exists ahead of the plain RuntimeException one below.
            log.warn("Agent execution failed after {}", elapsed(startedAtNanos), e);
            executionService.fail(job.getId(), describeFailure(job, e), e.inputTokens(), e.outputTokens(), e.totalTokens());
        } catch (RuntimeException e) {
            log.warn("Agent execution failed after {}", elapsed(startedAtNanos), e);
            executionService.fail(job.getId(), describeFailure(job, e));
        } finally {
            heartbeatMonitor.untrack(job.getId());
            MDC.remove(MDC_EXECUTION_ID);
            TenantContext.clear();
        }
    }

    /**
     * The message stored on the failed execution row. Routed through
     * LocalModelHint so a self-hosted run that named a model this machine
     * does not have says which models it DOES have -- the error is read hours
     * later out of the history UI, long after the operator could have run
     * `ollama list` themselves.
     *
     * Resolves the provider from the tenant rather than the row, because a
     * run can fail before anything provider-shaped was recorded.
     */
    private String describeFailure(AgentExecution job, RuntimeException e) {
        try {
            return localModelHint.enrich(tenantLlmProviderResolver.resolve(job.getTenantId()), e.getMessage());
        } catch (RuntimeException ignored) {
            // Enriching an error must never replace it with a different one.
            return e.getMessage();
        }
    }

    /** Wall-clock for one run, for the log line only -- the authoritative timing is startedAt/completedAt on the row. */
    private static Duration elapsed(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }
}
