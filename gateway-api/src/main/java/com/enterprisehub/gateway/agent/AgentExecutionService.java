package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.gateway.repository.AgentExecutionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
 * "coding-agent") -- repurposed from its original Week 1 meaning
 * ("SECURITY_PATCH", "CROSS_STACK_ALIGNMENT", categories for a
 * not-yet-built agent) now that real named agents exist, see
 * V6__agent_definitions.sql.
 */
@Service
public class AgentExecutionService {

    private final AgentExecutionRepository repository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentExecutionService(AgentExecutionRepository repository, AgentDefinitionRepository agentDefinitionRepository) {
        this.repository = repository;
        this.agentDefinitionRepository = agentDefinitionRepository;
    }

    /**
     * Validates the agent slug BEFORE persisting a row -- rejecting an
     * unknown/inactive agent with 400 here is better than silently queuing
     * a job AgentJobWorker can only discover is doomed once it claims it.
     * repositoryUrl/inputParameters are both optional (see
     * TriggerAgentExecutionRequest's javadoc) -- inputParameters is
     * JSON-serialized for storage (see V9__agent_execution_input_parameters.sql),
     * round-tripped by AgentJobWorker via deserializeInputParameters().
     */
    @Transactional
    public AgentExecution enqueue(UUID tenantId, String prompt, String agentSlug, String repositoryUrl, Map<String, String> inputParameters) {
        agentDefinitionRepository.findBySlugAndActiveTrue(agentSlug)
                .orElseThrow(() -> new AgentException(HttpStatus.BAD_REQUEST, "Unknown or inactive agent: " + agentSlug));

        AgentExecution execution = new AgentExecution();
        execution.setTenantId(tenantId);
        execution.setAgentType(agentSlug);
        execution.setTriggerSource("API");
        execution.setLlmProvider(LlmProvider.ANTHROPIC.name());
        execution.setPrompt(prompt);
        execution.setRepositoryUrl(repositoryUrl);
        execution.setInputParameters(serializeInputParameters(inputParameters));
        execution.setStatus("QUEUED");
        return repository.save(execution);
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

    @Transactional
    public void complete(UUID executionId, String reply, boolean toolWasUsed) {
        repository.findById(executionId).ifPresent(execution -> {
            execution.setStatus("SUCCEEDED");
            execution.setReply(reply);
            execution.setToolWasUsed(toolWasUsed);
            execution.setCompletedAt(Instant.now());
        });
    }

    @Transactional
    public void fail(UUID executionId, String errorMessage) {
        repository.findById(executionId).ifPresent(execution -> {
            execution.setStatus("FAILED");
            execution.setErrorMessage(errorMessage);
            execution.setCompletedAt(Instant.now());
        });
    }

    @Transactional(readOnly = true)
    public Optional<AgentExecution> findForTenant(UUID tenantId, UUID executionId) {
        return repository.findByIdAndTenantId(executionId, tenantId);
    }
}
