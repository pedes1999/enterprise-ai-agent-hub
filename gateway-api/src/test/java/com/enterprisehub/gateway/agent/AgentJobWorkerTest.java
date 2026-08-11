package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentJobWorkerTest {

    private AgentExecutionService executionService;
    private AgentPromptRunner agentPromptRunner;
    private AgentJobWorker worker;

    @BeforeEach
    void setUp() {
        executionService = mock(AgentExecutionService.class);
        agentPromptRunner = mock(AgentPromptRunner.class);
        worker = new AgentJobWorker(executionService, agentPromptRunner);
        // Every AgentExecution mocked in this test file leaves inputParameters
        // unset -- matches AgentExecutionService's own real "none stored" contract.
        when(executionService.deserializeInputParameters(any())).thenReturn(Map.of());
    }

    @AfterEach
    void clearTenantContext() {
        // Defensive: a bug in the worker leaking the sentinel or a stale
        // tenant id into later tests would be exactly the class of bug
        // TenantContext's own javadoc warns about.
        TenantContext.clear();
    }

    @Test
    void pollAndProcessOne_noQueuedJob_doesNothing() {
        when(executionService.claimNext()).thenReturn(Optional.empty());

        worker.pollAndProcessOne();

        verifyNoInteractions(agentPromptRunner);
        verify(executionService, never()).complete(any(), any(), anyBoolean());
        verify(executionService, never()).fail(any(), any());
    }

    @Test
    void pollAndProcessOne_claimsUnderWorkerSentinel() {
        when(executionService.claimNext()).thenAnswer(invocation -> {
            // Assert the sentinel is active AT THE MOMENT claimNext() is
            // called -- this is the one operation that's allowed to see
            // every tenant's queued rows.
            assertThat(TenantContext.get()).isEqualTo(TenantContext.SYSTEM_WORKER_TENANT_ID);
            return Optional.empty();
        });

        worker.pollAndProcessOne();

        assertThat(TenantContext.get()).isNull(); // cleared afterwards
    }

    @Test
    void pollAndProcessOne_success_switchesToRealTenantAndCompletes() {
        UUID tenantId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        AgentExecution job = new AgentExecution();
        job.setId(executionId);
        job.setTenantId(tenantId);
        job.setPrompt("list files");
        job.setAgentType("coding-agent");
        when(executionService.claimNext()).thenReturn(Optional.of(job));

        when(agentPromptRunner.run(eq(tenantId), eq(executionId.toString()), eq("coding-agent"), eq("list files"), eq(null), eq(Map.of())))
                .thenAnswer(invocation -> {
                    // The real tenant, not the sentinel, must be active
                    // while the agent actually runs.
                    assertThat(TenantContext.get()).isEqualTo(tenantId.toString());
                    return new ToolCallingChatEngine.ToolChatResult("here are the files", true);
                });

        worker.pollAndProcessOne();

        verify(executionService).complete(executionId, "here are the files", true);
        verify(executionService, never()).fail(any(), any());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void pollAndProcessOne_runnerThrows_marksFailedInsteadOfPropagating() {
        UUID tenantId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        AgentExecution job = new AgentExecution();
        job.setId(executionId);
        job.setTenantId(tenantId);
        job.setPrompt("do something");
        job.setAgentType("coding-agent");
        when(executionService.claimNext()).thenReturn(Optional.of(job));
        when(agentPromptRunner.run(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Anthropic API call failed: timeout"));

        worker.pollAndProcessOne(); // must not throw out of the scheduled method

        verify(executionService).fail(executionId, "Anthropic API call failed: timeout");
        verify(executionService, never()).complete(any(), any(), anyBoolean());
        assertThat(TenantContext.get()).isNull();
    }
}
