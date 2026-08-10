package com.enterprisehub.gateway.agent;

import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.gateway.repository.AgentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentExecutionServiceTest {

    private AgentExecutionRepository repository;
    private AgentDefinitionRepository agentDefinitionRepository;
    private AgentExecutionService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(AgentExecutionRepository.class);
        agentDefinitionRepository = mock(AgentDefinitionRepository.class);
        service = new AgentExecutionService(repository, agentDefinitionRepository);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.findBySlugAndActiveTrue("coding-agent"))
                .thenReturn(Optional.of(new AgentDefinition()));
    }

    @Test
    void enqueue_createsQueuedRowWithPromptAndTenantAndAgentSlug() {
        AgentExecution saved = service.enqueue(tenantId, "list files", "coding-agent");

        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getPrompt()).isEqualTo("list files");
        assertThat(saved.getStatus()).isEqualTo("QUEUED");
        assertThat(saved.getLlmProvider()).isEqualTo("ANTHROPIC");
        assertThat(saved.getAgentType()).isEqualTo("coding-agent");
        verify(repository).save(any(AgentExecution.class));
    }

    @Test
    void enqueue_unknownAgentSlug_rejectedBeforePersisting() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("does-not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enqueue(tenantId, "list files", "does-not-exist"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does-not-exist")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(repository);
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
}
