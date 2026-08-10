package com.enterprisehub.gateway.agent;

import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.repository.AgentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentExecutionServiceTest {

    private AgentExecutionRepository repository;
    private AgentExecutionService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(AgentExecutionRepository.class);
        service = new AgentExecutionService(repository);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void enqueue_createsQueuedRowWithPromptAndTenant() {
        AgentExecution saved = service.enqueue(tenantId, "list files");

        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getPrompt()).isEqualTo("list files");
        assertThat(saved.getStatus()).isEqualTo("QUEUED");
        assertThat(saved.getLlmProvider()).isEqualTo("ANTHROPIC");
        verify(repository).save(any(AgentExecution.class));
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
