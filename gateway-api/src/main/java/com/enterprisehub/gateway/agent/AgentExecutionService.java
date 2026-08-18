package com.enterprisehub.gateway.agent;

import com.enterprisehub.dto.AgentTokenUsageStats;
import com.enterprisehub.dto.ExecutionUsage;
import com.enterprisehub.dto.ToolExecutionRecord;
import com.enterprisehub.gateway.config.ExecutionLimitProperties;
import com.enterprisehub.gateway.tenant.TenantLlmProviderResolver;
import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.entity.ToolExecution;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.gateway.repository.AgentExecutionRepository;
import com.enterprisehub.gateway.repository.ToolExecutionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns every state transition of an agent_executions row: QUEUED (created
 * by an API caller) -> RUNNING (claimed by AgentJobWorker) ->
 * SUCCEEDED|FAILED (set by AgentJobWorker once AgentPromptRunner returns
 * or throws). Each method is its own short transaction -- claimNext() in
 * particular MUST commit quickly (it only flips one row to RUNNING) so the
 * FOR UPDATE row lock isn't held anywhere near as long as the actual agent
 * run that follows it.
 *
 * agentType stores the resolved AgentDefinition slug for this run (e.g.
 * "ticket-resolver") -- repurposed from its original Week 1 meaning
 * ("SECURITY_PATCH", "CROSS_STACK_ALIGNMENT", categories for a
 * not-yet-built agent) now that real named agents exist, see
 * V6__agent_definitions.sql.
 */
@Service
public class AgentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionService.class);

    /** The only two statuses that count against a tenant's concurrency cap -- SUCCEEDED/FAILED rows don't hold anything open. */
    private static final List<String> ACTIVE_STATUSES = List.of("QUEUED", "RUNNING");

    private final AgentExecutionRepository repository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final ExecutionLimitProperties executionLimitProperties;
    private final TenantLlmProviderResolver tenantLlmProviderResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentExecutionService(AgentExecutionRepository repository, AgentDefinitionRepository agentDefinitionRepository,
                                  ToolExecutionRepository toolExecutionRepository, ExecutionLimitProperties executionLimitProperties,
                                  TenantLlmProviderResolver tenantLlmProviderResolver) {
        this.repository = repository;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.executionLimitProperties = executionLimitProperties;
        this.tenantLlmProviderResolver = tenantLlmProviderResolver;
    }

    /**
     * Validates the agent slug BEFORE persisting a row -- rejecting an
     * unknown/inactive agent with 400 here is better than silently queuing
     * a job AgentJobWorker can only discover is doomed once it claims it.
     * repositoryUrl/inputParameters are both optional (see
     * TriggerAgentExecutionRequest's javadoc) -- inputParameters is
     * JSON-serialized for storage (see V9__agent_execution_input_parameters.sql),
     * round-tripped by AgentJobWorker via deserializeInputParameters().
     *
     * Also validates this definition's own required_inputs (see
     * validateRequiredInputs()) -- the generalized replacement for what
     * used to be a single hardcoded "prompt is required" check in
     * AgentExecutionController, and rejects (429, not a silent queue) once
     * this tenant already has executionLimitProperties.maxConcurrentPerTenant()
     * rows QUEUED or RUNNING -- protects a metered E2B/Anthropic account
     * from a bug, a misbehaving agent loop, or a user clicking repeatedly.
     * Logged at WARN so a tenant that regularly hits the ceiling is visible
     * (a real product signal -- maybe their limit genuinely needs raising --
     * not just an error to swallow).
     */
    @Transactional
    public AgentExecution enqueue(EnqueueExecutionCommand command) {
        UUID tenantId = command.tenantId();
        String agentSlug = command.agentSlug();
        String repositoryUrl = command.repositoryUrl();
        Integer maxTokens = command.maxTokens();

        AgentDefinition definition = agentDefinitionRepository.findBySlugAndActiveTrue(agentSlug)
                .orElseThrow(() -> new AgentException(HttpStatus.BAD_REQUEST, "Unknown or inactive agent: " + agentSlug));

        validateRequiredInputs(definition, command.prompt(), repositoryUrl, command.inputParameters());
        if (maxTokens != null && maxTokens <= 0) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "maxTokens must be positive");
        }

        ExecutionUsage usage = getUsage(tenantId);
        long activeCount = usage.active();
        int limit = usage.limit();
        if (activeCount >= limit) {
            log.warn("Tenant {} rejected at the concurrency cap ({} active executions, limit {})", tenantId, activeCount, limit);
            throw new AgentException(HttpStatus.TOO_MANY_REQUESTS,
                    "This tenant already has " + activeCount + " agent executions in progress (limit " + limit
                            + ") -- wait for one to finish before starting another.");
        }

        AgentExecution execution = new AgentExecution();
        execution.setTenantId(tenantId);
        execution.setAgentType(agentSlug);
        execution.setTriggerSource("API");
        execution.setLlmProvider(tenantLlmProviderResolver.resolve(tenantId).name());
        // prompt is optional at the DTO/validation level (see validateRequiredInputs()) but
        // the column itself is NOT NULL (V5__agent_execution_queue.sql) -- coerce null to the
        // same "" sentinel that migration already uses, rather than letting a caller for any
        // agent whose requiredInputs doesn't include "prompt" crash the insert. Both seeded
        // agents currently require prompt, but that's a per-AgentDefinition choice, not a
        // guarantee -- this stays defensive for the next one that doesn't.
        execution.setPrompt(command.prompt() == null ? "" : command.prompt());
        execution.setRepositoryUrl(repositoryUrl);
        // Only meaningful paired with a repository -- never persisted on its own.
        String repositoryBranch = command.repositoryBranch();
        execution.setRepositoryBranch((repositoryUrl == null || repositoryUrl.isBlank() || repositoryBranch == null || repositoryBranch.isBlank())
                ? null : repositoryBranch);
        execution.setInputParameters(serializeInputParameters(command.inputParameters()));
        execution.setMaxTokensOverride(maxTokens);
        execution.setTriggeredBy(command.triggeredBy());
        execution.setParentExecutionId(command.parentExecutionId());
        execution.setStatus("QUEUED");
        return repository.save(execution);
    }

    private static final String INPUT_PARAMETERS_PREFIX = "inputParameters:";

    /**
     * Collects EVERY unmet requirement (not just the first) so a caller
     * fixes its request in one round trip instead of playing whack-a-mole.
     * Fixed vocabulary -- see V10__agent_definition_required_inputs.sql's
     * comment for the full list. An unrecognized requirement string is a
     * data-integrity problem with the AgentDefinition row itself (same
     * posture as ToolCatalog's unknown-tool-name error), not a caller error.
     */
    private void validateRequiredInputs(AgentDefinition definition, String prompt, String repositoryUrl, Map<String, String> inputParameters) {
        List<String> missing = new ArrayList<>();
        for (String requirement : definition.getRequiredInputs()) {
            if (!isRequirementSatisfied(definition, requirement, prompt, repositoryUrl, inputParameters)) {
                missing.add(displayName(requirement));
            }
        }
        if (!missing.isEmpty()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "Missing required input(s): " + String.join(", ", missing));
        }
    }

    private boolean isRequirementSatisfied(AgentDefinition definition, String requirement, String prompt,
                                            String repositoryUrl, Map<String, String> inputParameters) {
        if ("prompt".equals(requirement)) {
            return prompt != null && !prompt.isBlank();
        }
        if ("repositoryUrl".equals(requirement)) {
            return repositoryUrl != null && !repositoryUrl.isBlank();
        }
        if (requirement.startsWith(INPUT_PARAMETERS_PREFIX)) {
            String key = requirement.substring(INPUT_PARAMETERS_PREFIX.length());
            String value = inputParameters == null ? null : inputParameters.get(key);
            return value != null && !value.isBlank();
        }
        throw new AgentException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Agent definition '" + definition.getSlug() + "' references unrecognized required input '" + requirement + "'");
    }

    /** "inputParameters:ticketKey" reads as "inputParameters.ticketKey" in a user-facing error -- prompt/repositoryUrl are already display-ready. */
    private String displayName(String requirement) {
        if (requirement.startsWith(INPUT_PARAMETERS_PREFIX)) {
            return "inputParameters." + requirement.substring(INPUT_PARAMETERS_PREFIX.length());
        }
        return requirement;
    }

    private String serializeInputParameters(Map<String, String> inputParameters) {
        if (inputParameters == null || inputParameters.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(inputParameters);
        } catch (Exception e) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "inputParameters could not be serialized: " + e.getMessage());
        }
    }

    /** Deserializes a persisted execution's inputParameters back into a map -- empty (not null) if none were given. */
    public Map<String, String> deserializeInputParameters(AgentExecution execution) {
        String json = execution.getInputParameters();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new AgentException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored inputParameters for execution " + execution.getId() + " could not be parsed: " + e.getMessage());
        }
    }

    /**
     * Must be called with TenantContext set to TenantContext.SYSTEM_WORKER_TENANT_ID
     * (see AgentJobWorker) -- this is the one operation in the system that
     * needs to see queued jobs across every tenant, not just one.
     */
    @Transactional
    public Optional<AgentExecution> claimNext() {
        return repository.claimNextQueued().map(execution -> {
            execution.setStatus("RUNNING");
            execution.setStartedAt(Instant.now());
            return execution;
        });
    }

    /**
     * Refreshes the liveness stamp for every execution the calling instance
     * still owns -- see ExecutionHeartbeatMonitor for who calls this and how
     * often. No-ops on an empty set rather than issuing a pointless UPDATE
     * (the common case: an idle instance running nothing).
     */
    @Transactional
    public void heartbeat(Collection<UUID> executionIds) {
        if (executionIds.isEmpty()) {
            return;
        }
        repository.heartbeat(executionIds, Instant.now());
    }

    /**
     * Fails every RUNNING execution whose owner has stopped stamping it --
     * the recovery half of the lockout described in
     * V32__agent_execution_heartbeat.sql. Marked FAILED rather than given a
     * new terminal status of its own: from every consumer's point of view
     * (the concurrency cap, GET /agents/executions, the history UI) an
     * abandoned run IS a failed one, and errorMessage carries the specific
     * reason, so nothing downstream needs to learn a new status value.
     *
     * Returns how many rows were reaped so the caller can log it -- a
     * non-zero count here means an instance died mid-run, which is worth
     * seeing rather than silently repairing.
     */
    @Transactional
    public int reapStaleRunning(Duration staleAfter) {
        List<AgentExecution> stale = repository.findStaleRunning(Instant.now().minus(staleAfter));
        for (AgentExecution execution : stale) {
            execution.setStatus("FAILED");
            execution.setErrorMessage("Execution was abandoned -- the worker running it stopped reporting for more than "
                    + staleAfter.toMinutes() + " minute(s) (most likely the app was restarted or killed mid-run).");
            execution.setCompletedAt(Instant.now());
        }
        return stale.size();
    }

    @Transactional
    public void complete(UUID executionId, String reply, boolean toolWasUsed) {
        complete(executionId, reply, toolWasUsed, null, null, null);
    }

    /** Same as the 3-arg overload, additionally recording the run's summed token usage -- see ToolChatResult's javadoc for why these are Integers, not ints. */
    @Transactional
    public void complete(UUID executionId, String reply, boolean toolWasUsed, Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        repository.findById(executionId).ifPresent(execution -> {
            execution.setStatus("SUCCEEDED");
            execution.setReply(reply);
            execution.setToolWasUsed(toolWasUsed);
            execution.setInputTokens(inputTokens);
            execution.setOutputTokens(outputTokens);
            execution.setTotalTokens(totalTokens);
            execution.setCompletedAt(Instant.now());
        });
    }

    @Transactional
    public void fail(UUID executionId, String errorMessage) {
        fail(executionId, errorMessage, null, null, null);
    }

    /** Same as the 2-arg overload, additionally recording token usage for a run that DID reach the model before failing (e.g. hit the round cap) -- the exception-before-any-call path has no usage to report, so it keeps using the 2-arg overload. */
    @Transactional
    public void fail(UUID executionId, String errorMessage, Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        repository.findById(executionId).ifPresent(execution -> {
            execution.setStatus("FAILED");
            execution.setErrorMessage(errorMessage);
            execution.setInputTokens(inputTokens);
            execution.setOutputTokens(outputTokens);
            execution.setTotalTokens(totalTokens);
            execution.setCompletedAt(Instant.now());
        });
    }

    @Transactional(readOnly = true)
    public Optional<AgentExecution> findForTenant(UUID tenantId, UUID executionId) {
        return repository.findByIdAndTenantId(executionId, tenantId);
    }

    /**
     * Backs GET /agents/executions/usage -- lets the frontend show "3 / 5
     * executions running" before a caller submits a trigger request. Same
     * count enqueue()'s own concurrency check uses (QUEUED + RUNNING),
     * extracted here so both stay in sync instead of two separate queries
     * that could drift.
     */
    @Transactional(readOnly = true)
    public ExecutionUsage getUsage(UUID tenantId) {
        long active = repository.countByTenantIdAndStatusIn(tenantId, ACTIVE_STATUSES);
        return new ExecutionUsage(active, executionLimitProperties.maxConcurrentPerTenant());
    }

    /**
     * Backs GET /agents/executions/token-usage-stats -- gives a trigger
     * form a concrete reference point ("past runs used ~X-Y tokens") for
     * what to put in a maxTokens override, instead of a blind guess. See
     * AgentExecutionRepository.tokenUsageStatsRaw()'s javadoc for the
     * "always one row, zeros/nulls for no data" contract this relies on.
     */
    @Transactional(readOnly = true)
    public AgentTokenUsageStats getTokenUsageStats(UUID tenantId, String agentSlug) {
        Object[] row = repository.tokenUsageStatsRaw(tenantId, agentSlug);
        long sampleCount = ((Number) row[0]).longValue();
        Integer minTokens = row[1] == null ? null : ((Number) row[1]).intValue();
        Double avgTokens = row[2] == null ? null : ((Number) row[2]).doubleValue();
        Integer maxTokens = row[3] == null ? null : ((Number) row[3]).intValue();
        return new AgentTokenUsageStats(agentSlug, sampleCount, minTokens, avgTokens, maxTokens);
    }

    /** Backs GET /agents/executions -- status is optional (null/blank means "every status"). Tenant-scoped by RLS, same as every other query here. */
    @Transactional(readOnly = true)
    public Page<AgentExecution> list(UUID tenantId, String status, Pageable pageable) {
        if (status == null || status.isBlank()) {
            return repository.findByTenantId(tenantId, pageable);
        }
        return repository.findByTenantIdAndStatus(tenantId, status, pageable);
    }

    /**
     * Backs GET /agents/executions/{id}/tool-executions -- the ordered
     * trace a teammate opens to verify what an agent actually did. 404s if
     * the execution itself doesn't exist or belongs to another tenant
     * (checked explicitly for a clear error message; tool_executions' own
     * RLS policy would also silently return nothing for a wrong tenant,
     * but that's indistinguishable from "no tools ran yet").
     */
    @Transactional(readOnly = true)
    public List<ToolExecutionRecord> getToolExecutions(UUID tenantId, UUID executionId) {
        findForTenant(tenantId, executionId)
                .orElseThrow(() -> new AgentException(HttpStatus.NOT_FOUND, "No execution with id " + executionId));

        return toolExecutionRepository.findByTenantIdAndExecutionIdOrderByCreatedAtAsc(tenantId, executionId.toString())
                .stream()
                .map(this::toToolExecutionRecord)
                .toList();
    }

    /**
     * Backs GET /agents/executions/{id}/children -- every execution
     * delegate_to_agent queued from this one (see
     * V25__agent_execution_parent_and_planner.sql). Empty, not 404, for an
     * execution that exists but never delegated anything -- 404 is reserved
     * for the parent itself not existing/belonging to another tenant, same
     * distinction getToolExecutions() already makes.
     */
    @Transactional(readOnly = true)
    public List<AgentExecution> getChildren(UUID tenantId, UUID executionId) {
        findForTenant(tenantId, executionId)
                .orElseThrow(() -> new AgentException(HttpStatus.NOT_FOUND, "No execution with id " + executionId));

        return repository.findByTenantIdAndParentExecutionIdOrderByCreatedAtAsc(tenantId, executionId);
    }

    private ToolExecutionRecord toToolExecutionRecord(ToolExecution toolExecution) {
        return new ToolExecutionRecord(
                toolExecution.getToolName(), toolExecution.getDurationMs(), toolExecution.getOutcome(),
                toolExecution.getErrorMessage(), toolExecution.getCreatedAt());
    }
}
