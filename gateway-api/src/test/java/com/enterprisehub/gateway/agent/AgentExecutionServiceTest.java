package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.dto.ToolExecutionRecord;
import com.enterprisehub.gateway.config.ExecutionLimitProperties;
import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.entity.ToolExecution;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.gateway.repository.AgentExecutionRepository;
import com.enterprisehub.gateway.repository.ToolExecutionRepository;
import com.enterprisehub.gateway.tenant.TenantLlmProviderResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentExecutionServiceTest {

    private AgentExecutionRepository repository;
    private AgentDefinitionRepository agentDefinitionRepository;
    private ToolExecutionRepository toolExecutionRepository;
    private TenantLlmProviderResolver tenantLlmProviderResolver;
    private AgentExecutionService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(AgentExecutionRepository.class);
        agentDefinitionRepository = mock(AgentDefinitionRepository.class);
        toolExecutionRepository = mock(ToolExecutionRepository.class);
        tenantLlmProviderResolver = mock(TenantLlmProviderResolver.class);
        when(tenantLlmProviderResolver.resolve(any())).thenReturn(LlmProvider.ANTHROPIC);
        service = new AgentExecutionService(repository, agentDefinitionRepository, toolExecutionRepository, new ExecutionLimitProperties(5),
                tenantLlmProviderResolver);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.findBySlugAndActiveTrue("coding-agent"))
                .thenReturn(Optional.of(new AgentDefinition()));
        // Mockito's default (0) for an unstubbed long-returning method matches
        // "no active executions yet" -- every existing test below relies on
        // this being the implicit case unless a test overrides it.
    }

    @Test
    void enqueue_createsQueuedRowWithPromptAndTenantAndAgentSlug() {
        AgentExecution saved = service.enqueue(tenantId, "list files", "coding-agent", null, null, null);

        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getPrompt()).isEqualTo("list files");
        assertThat(saved.getStatus()).isEqualTo("QUEUED");
        assertThat(saved.getLlmProvider()).isEqualTo("ANTHROPIC");
        assertThat(saved.getAgentType()).isEqualTo("coding-agent");
        verify(repository).save(any(AgentExecution.class));
    }

    @Test
    void enqueue_llmProvider_reflectsWhateverTheTenantResolvesTo_notAlwaysAnthropic() {
        when(tenantLlmProviderResolver.resolve(tenantId)).thenReturn(LlmProvider.LOCAL);

        AgentExecution saved = service.enqueue(tenantId, "list files", "coding-agent", null, null, null);

        assertThat(saved.getLlmProvider()).isEqualTo("LOCAL");
    }

    @Test
    void enqueue_repositoryUrlAndInputParameters_persistedOnTheRow() {
        AgentExecution saved = service.enqueue(tenantId, "fix it", "coding-agent",
                "https://github.com/org/repo.git", null, Map.of("text", "Ticket: fix the bug"));

        assertThat(saved.getRepositoryUrl()).isEqualTo("https://github.com/org/repo.git");
        assertThat(saved.getInputParameters()).contains("\"text\"").contains("Ticket: fix the bug");
    }

    @Test
    void enqueue_repositoryBranchGivenWithUrl_persisted() {
        AgentExecution saved = service.enqueue(tenantId, "fix it", "coding-agent",
                "https://github.com/org/repo.git", "feature/my-branch", Map.of());

        assertThat(saved.getRepositoryBranch()).isEqualTo("feature/my-branch");
    }

    @Test
    void enqueue_repositoryBranchGiven_butNoRepositoryUrl_neverPersisted() {
        // A branch with no repository doesn't mean anything -- silently
        // dropped rather than stored as orphaned state.
        AgentExecution saved = service.enqueue(tenantId, "fix it", "coding-agent", null, "feature/my-branch", Map.of());

        assertThat(saved.getRepositoryBranch()).isNull();
    }

    @Test
    void enqueue_blankRepositoryBranch_treatedSameAsOmitted() {
        AgentExecution saved = service.enqueue(tenantId, "fix it", "coding-agent",
                "https://github.com/org/repo.git", "   ", Map.of());

        assertThat(saved.getRepositoryBranch()).isNull();
    }

    @Test
    void deserializeInputParameters_roundTripsWhatEnqueueSerialized() {
        AgentExecution saved = service.enqueue(tenantId, "fix it", "coding-agent",
                "https://github.com/org/repo.git", null, Map.of("text", "Ticket: fix the bug"));

        assertThat(service.deserializeInputParameters(saved)).isEqualTo(Map.of("text", "Ticket: fix the bug"));
    }

    @Test
    void deserializeInputParameters_noneStored_returnsEmptyMapNotNull() {
        AgentExecution saved = service.enqueue(tenantId, "list files", "coding-agent", null, null, null);

        assertThat(service.deserializeInputParameters(saved)).isEmpty();
    }

    private AgentDefinition definitionWithRequiredInputs(String slug, String... requiredInputs) {
        AgentDefinition definition = new AgentDefinition();
        definition.setSlug(slug);
        definition.setRequiredInputs(List.of(requiredInputs));
        return definition;
    }

    @Test
    void enqueue_generalAssistantStyle_promptOnly_succeeds() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("general-assistant"))
                .thenReturn(Optional.of(definitionWithRequiredInputs("general-assistant", "prompt")));

        AgentExecution saved = service.enqueue(tenantId, "Hello", "general-assistant", null, null, null);

        assertThat(saved.getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void enqueue_generalAssistantStyle_blankPrompt_rejected() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("general-assistant"))
                .thenReturn(Optional.of(definitionWithRequiredInputs("general-assistant", "prompt")));

        assertThatThrownBy(() -> service.enqueue(tenantId, "   ", "general-assistant", null, null, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Missing required input(s): prompt")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(repository);
    }

    @Test
    void enqueue_codingAgentStyle_repositoryUrlOnly_succeeds() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("coding-agent"))
                .thenReturn(Optional.of(definitionWithRequiredInputs("coding-agent", "repositoryUrl")));

        AgentExecution saved = service.enqueue(tenantId, "", "coding-agent", "https://github.com/org/repo.git", null, Map.of());

        assertThat(saved.getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void enqueue_nullPrompt_coercedToEmptyString_notPersistedAsNull() {
        // The frontend's trigger form only sends a prompt field for agents whose
        // requiredInputs includes "prompt" -- for coding-agent (requiredInputs =
        // ["repositoryUrl"]) it sends prompt: null. The agent_executions.prompt
        // column is NOT NULL (see V5__agent_execution_queue.sql), so a literal
        // null must never reach repository.save() or the insert fails.
        when(agentDefinitionRepository.findBySlugAndActiveTrue("coding-agent"))
                .thenReturn(Optional.of(definitionWithRequiredInputs("coding-agent", "repositoryUrl")));

        AgentExecution saved = service.enqueue(tenantId, null, "coding-agent", "https://github.com/org/repo.git", null, null);

        assertThat(saved.getPrompt()).isEqualTo("");
    }

    @Test
    void enqueue_codingAgentStyle_missingRepositoryUrl_rejected() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("coding-agent"))
                .thenReturn(Optional.of(definitionWithRequiredInputs("coding-agent", "repositoryUrl")));

        assertThatThrownBy(() -> service.enqueue(tenantId, "some prompt", "coding-agent", null, null, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Missing required input(s): repositoryUrl")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(repository);
    }

    @Test
    void enqueue_requiredInputParametersKey_missing_rejected() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("ticket-agent"))
                .thenReturn(Optional.of(definitionWithRequiredInputs("ticket-agent", "inputParameters:ticketKey")));

        assertThatThrownBy(() -> service.enqueue(tenantId, "", "ticket-agent", null, null, Map.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Missing required input(s): inputParameters.ticketKey")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(repository);
    }

    @Test
    void enqueue_requiredInputParametersKey_present_succeeds() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("ticket-agent"))
                .thenReturn(Optional.of(definitionWithRequiredInputs("ticket-agent", "inputParameters:ticketKey")));

        AgentExecution saved = service.enqueue(tenantId, "", "ticket-agent", null, null, Map.of("ticketKey", "TICKET-123"));

        assertThat(saved.getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void enqueue_multipleMissingRequiredInputs_allReportedTogether() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("ticket-pr-agent"))
                .thenReturn(Optional.of(definitionWithRequiredInputs("ticket-pr-agent", "repositoryUrl", "inputParameters:ticketKey")));

        assertThatThrownBy(() -> service.enqueue(tenantId, "", "ticket-pr-agent", null, null, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Missing required input(s): repositoryUrl, inputParameters.ticketKey")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(repository);
    }

    @Test
    void enqueue_unknownAgentSlug_rejectedBeforePersisting() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("does-not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enqueue(tenantId, "list files", "does-not-exist", null, null, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does-not-exist")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(repository);
    }

    @Test
    void enqueue_atConcurrencyLimit_rejectedWithTooManyRequests_neverPersists() {
        when(repository.countByTenantIdAndStatusIn(tenantId, List.of("QUEUED", "RUNNING"))).thenReturn(5L);

        assertThatThrownBy(() -> service.enqueue(tenantId, "list files", "coding-agent", null, null, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("5")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(repository, never()).save(any());
    }

    @Test
    void enqueue_belowConcurrencyLimit_succeeds() {
        when(repository.countByTenantIdAndStatusIn(tenantId, List.of("QUEUED", "RUNNING"))).thenReturn(4L);

        AgentExecution saved = service.enqueue(tenantId, "list files", "coding-agent", null, null, null);

        assertThat(saved.getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void enqueue_overConcurrencyLimit_stillRejected() {
        when(repository.countByTenantIdAndStatusIn(tenantId, List.of("QUEUED", "RUNNING"))).thenReturn(9L);

        assertThatThrownBy(() -> service.enqueue(tenantId, "list files", "coding-agent", null, null, null))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void enqueue_concurrencyCheck_isScopedToTheCallingTenant_notGlobal() {
        UUID otherTenant = UUID.randomUUID();
        when(repository.countByTenantIdAndStatusIn(otherTenant, List.of("QUEUED", "RUNNING"))).thenReturn(5L);
        // tenantId itself has nothing active (default 0 stub) -- a busy
        // OTHER tenant must never affect this one.

        AgentExecution saved = service.enqueue(tenantId, "list files", "coding-agent", null, null, null);

        assertThat(saved.getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void claimNext_noQueuedRow_returnsEmpty() {
        when(repository.claimNextQueued()).thenReturn(Optional.empty());

        assertThat(service.claimNext()).isEmpty();
    }

    @Test
    void claimNext_flipsToRunningAndSetsStartedAt() {
        AgentExecution execution = new AgentExecution();
        execution.setId(UUID.randomUUID());
        execution.setStatus("QUEUED");
        when(repository.claimNextQueued()).thenReturn(Optional.of(execution));

        Optional<AgentExecution> claimed = service.claimNext();

        assertThat(claimed).isPresent();
        assertThat(claimed.get().getStatus()).isEqualTo("RUNNING");
        assertThat(claimed.get().getStartedAt()).isNotNull();
    }

    @Test
    void complete_setsSucceededWithReplyAndToolFlag() {
        UUID id = UUID.randomUUID();
        AgentExecution execution = new AgentExecution();
        execution.setId(id);
        execution.setStatus("RUNNING");
        when(repository.findById(id)).thenReturn(Optional.of(execution));

        service.complete(id, "here are the files", true);

        assertThat(execution.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(execution.getReply()).isEqualTo("here are the files");
        assertThat(execution.getToolWasUsed()).isTrue();
        assertThat(execution.getCompletedAt()).isNotNull();
    }

    @Test
    void complete_unknownId_doesNothing_doesNotThrow() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        service.complete(UUID.randomUUID(), "reply", false);
        // no exception -- nothing to assert beyond that
    }

    @Test
    void fail_setsFailedWithErrorMessage() {
        UUID id = UUID.randomUUID();
        AgentExecution execution = new AgentExecution();
        execution.setId(id);
        execution.setStatus("RUNNING");
        when(repository.findById(id)).thenReturn(Optional.of(execution));

        service.fail(id, "Anthropic API call failed: timeout");

        assertThat(execution.getStatus()).isEqualTo("FAILED");
        assertThat(execution.getErrorMessage()).isEqualTo("Anthropic API call failed: timeout");
        assertThat(execution.getCompletedAt()).isNotNull();
    }

    @Test
    void findForTenant_delegatesToTenantScopedLookup() {
        UUID id = UUID.randomUUID();
        AgentExecution execution = new AgentExecution();
        execution.setId(id);
        execution.setTenantId(tenantId);
        when(repository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(execution));

        assertThat(service.findForTenant(tenantId, id)).contains(execution);
    }

    @Test
    void list_noStatusFilter_delegatesToFindByTenantId() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        AgentExecution execution = new AgentExecution();
        org.springframework.data.domain.Page<AgentExecution> page =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(execution));
        when(repository.findByTenantId(tenantId, pageable)).thenReturn(page);

        assertThat(service.list(tenantId, null, pageable).getContent()).containsExactly(execution);
        verify(repository, never()).findByTenantIdAndStatus(any(), any(), any());
    }

    @Test
    void list_blankStatusFilter_treatedAsNoFilter() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(repository.findByTenantId(any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        service.list(tenantId, "  ", pageable);

        verify(repository).findByTenantId(tenantId, pageable);
        verify(repository, never()).findByTenantIdAndStatus(any(), any(), any());
    }

    @Test
    void list_withStatusFilter_delegatesToFindByTenantIdAndStatus() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        AgentExecution execution = new AgentExecution();
        org.springframework.data.domain.Page<AgentExecution> page =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(execution));
        when(repository.findByTenantIdAndStatus(tenantId, "RUNNING", pageable)).thenReturn(page);

        assertThat(service.list(tenantId, "RUNNING", pageable).getContent()).containsExactly(execution);
        verify(repository, never()).findByTenantId(any(), any());
    }

    @Test
    void getToolExecutions_knownExecution_returnsOrderedTrace() {
        UUID executionId = UUID.randomUUID();
        AgentExecution execution = new AgentExecution();
        execution.setId(executionId);
        execution.setTenantId(tenantId);
        when(repository.findByIdAndTenantId(executionId, tenantId)).thenReturn(Optional.of(execution));

        ToolExecution first = new ToolExecution();
        first.setToolName("git_clone");
        first.setDurationMs(120);
        first.setOutcome("SUCCESS");
        first.setCreatedAt(Instant.now());
        ToolExecution second = new ToolExecution();
        second.setToolName("run_shell_command");
        second.setDurationMs(45);
        second.setOutcome("FAILURE");
        second.setErrorMessage("exit code 1");
        second.setCreatedAt(Instant.now());
        when(toolExecutionRepository.findByTenantIdAndExecutionIdOrderByCreatedAtAsc(tenantId, executionId.toString()))
                .thenReturn(List.of(first, second));

        List<ToolExecutionRecord> trace = service.getToolExecutions(tenantId, executionId);

        assertThat(trace).hasSize(2);
        assertThat(trace.get(0).toolName()).isEqualTo("git_clone");
        assertThat(trace.get(0).outcome()).isEqualTo("SUCCESS");
        assertThat(trace.get(1).toolName()).isEqualTo("run_shell_command");
        assertThat(trace.get(1).errorMessage()).isEqualTo("exit code 1");
    }

    @Test
    void getToolExecutions_noExecution_throwsNotFound_neverQueriesToolExecutions() {
        UUID executionId = UUID.randomUUID();
        when(repository.findByIdAndTenantId(executionId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getToolExecutions(tenantId, executionId))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(toolExecutionRepository);
    }

    @Test
    void getToolExecutions_noToolsRanYet_returnsEmptyList() {
        UUID executionId = UUID.randomUUID();
        AgentExecution execution = new AgentExecution();
        execution.setId(executionId);
        when(repository.findByIdAndTenantId(executionId, tenantId)).thenReturn(Optional.of(execution));
        when(toolExecutionRepository.findByTenantIdAndExecutionIdOrderByCreatedAtAsc(tenantId, executionId.toString()))
                .thenReturn(List.of());

        assertThat(service.getToolExecutions(tenantId, executionId)).isEmpty();
    }

    @Test
    void getUsage_reflectsActiveCountAndConfiguredLimit() {
        when(repository.countByTenantIdAndStatusIn(tenantId, List.of("QUEUED", "RUNNING"))).thenReturn(3L);

        var usage = service.getUsage(tenantId);

        assertThat(usage.active()).isEqualTo(3L);
        assertThat(usage.limit()).isEqualTo(5);
    }

    @Test
    void getUsage_isScopedToTheCallingTenant_notGlobal() {
        UUID otherTenant = UUID.randomUUID();
        when(repository.countByTenantIdAndStatusIn(otherTenant, List.of("QUEUED", "RUNNING"))).thenReturn(5L);
        // tenantId itself has nothing active (default 0 stub).

        assertThat(service.getUsage(tenantId).active()).isZero();
    }
}
