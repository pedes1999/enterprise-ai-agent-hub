package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentJobWorkerTest {

    private AgentExecutionService executionService;
    private AgentPromptRunner agentPromptRunner;
    private ExecutionHeartbeatMonitor heartbeatMonitor;
    private AgentJobWorker worker;

    @BeforeEach
    void setUp() {
        executionService = mock(AgentExecutionService.class);
        agentPromptRunner = mock(AgentPromptRunner.class);
        heartbeatMonitor = mock(ExecutionHeartbeatMonitor.class);
        worker = new AgentJobWorker(executionService, agentPromptRunner, heartbeatMonitor);
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
        verify(executionService, never()).complete(any(), any(), anyBoolean(), any(), any(), any());
        verify(executionService, never()).fail(any(), any(), any(), any(), any());
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
        UUID userId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        AgentExecution job = new AgentExecution();
        job.setId(executionId);
        job.setTenantId(tenantId);
        job.setTriggeredBy(userId);
        job.setPrompt("list files");
        job.setAgentType("coding-agent");
        when(executionService.claimNext()).thenReturn(Optional.of(job));

        // Can no longer match AgentRunRequest by record value equality now
        // that it carries a cancellationCheck lambda -- AgentJobWorker
        // builds a fresh one per call, and two independently-created
        // lambdas are never equal() to each other. argThat checks every
        // OTHER field instead, plus that a (non-comparable) check was set.
        when(agentPromptRunner.run(argThat(request ->
                request.tenantId().equals(tenantId)
                        && request.userId().equals(userId)
                        && request.executionId().equals(executionId.toString())
                        && request.agentSlug().equals("coding-agent")
                        && "list files".equals(request.prompt())
                        && Map.of().equals(request.inputParameters())
                        && request.cancellationCheck() != null)))
                .thenAnswer(invocation -> {
                    // The real tenant, not the sentinel, must be active
                    // while the agent actually runs.
                    assertThat(TenantContext.get()).isEqualTo(tenantId.toString());
                    return new ToolCallingChatEngine.ToolChatResult("here are the files", true, false, null);
                });

        worker.pollAndProcessOne();

        verify(executionService).complete(executionId, "here are the files", true, null, null, null);
        verify(executionService, never()).fail(any(), any(), any(), any(), any());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void pollAndProcessOne_resultIncomplete_marksFailedWithTheIncompleteReason() {
        // incomplete==true means the model never reached a real stopping
        // point (truncated by max_tokens, or ran out of tool-call rounds) --
        // reporting that as a clean SUCCEEDED with a half-finished reply and
        // no error anywhere is exactly the silent-failure gap this guards.
        UUID tenantId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        AgentExecution job = new AgentExecution();
        job.setId(executionId);
        job.setTenantId(tenantId);
        job.setPrompt("fix the bug");
        job.setAgentType("ticket-resolver");
        when(executionService.claimNext()).thenReturn(Optional.of(job));
        when(agentPromptRunner.run(any())).thenReturn(
                new ToolCallingChatEngine.ToolChatResult("Let me check if there's a", true, true,
                        "Agent used all 14 allowed tool-call rounds without finishing."));

        worker.pollAndProcessOne();

        verify(executionService).fail(executionId, "Agent used all 14 allowed tool-call rounds without finishing.", null, null, null);
        verify(executionService, never()).complete(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void pollAndProcessOne_completeButBlankReplyDespiteToolUse_marksFailed() {
        // A genuine final answer (incomplete==false) that's still blank after
        // using tools is its own failure signal -- most often every tool call
        // the model tried actually failed (e.g. the sandbox sidecar was
        // unreachable) and it gave up with nothing to show for it. Those
        // individual tool failures are already recorded in tool_executions,
        // but nothing previously made the execution's own status reflect that.
        UUID tenantId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        AgentExecution job = new AgentExecution();
        job.setId(executionId);
        job.setTenantId(tenantId);
        job.setPrompt("fix the bug");
        job.setAgentType("ticket-resolver");
        when(executionService.claimNext()).thenReturn(Optional.of(job));
        when(agentPromptRunner.run(any()))
                .thenReturn(new ToolCallingChatEngine.ToolChatResult("", true, false, null));

        worker.pollAndProcessOne();

        verify(executionService).fail(eq(executionId), contains("tool-call trace"), any(), any(), any());
        verify(executionService, never()).complete(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void pollAndProcessOne_blankReplyButNoToolUse_stillCompletesNormally() {
        // No tools were even offered/used (e.g. general-assistant answering
        // "" is a legitimate, if unusual, direct answer) -- blank-reply-as-
        // failure is specifically about tool use going nowhere, not about
        // every blank reply ever.
        UUID tenantId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        AgentExecution job = new AgentExecution();
        job.setId(executionId);
        job.setTenantId(tenantId);
        job.setPrompt("say nothing");
        job.setAgentType("general-assistant");
        when(executionService.claimNext()).thenReturn(Optional.of(job));
        when(agentPromptRunner.run(any()))
                .thenReturn(new ToolCallingChatEngine.ToolChatResult("", false, false, null));

        worker.pollAndProcessOne();

        verify(executionService).complete(executionId, "", false, null, null, null);
        verify(executionService, never()).fail(any(), any(), any(), any(), any());
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
        when(agentPromptRunner.run(any()))
                .thenThrow(new RuntimeException("Anthropic API call failed: timeout"));

        worker.pollAndProcessOne(); // must not throw out of the scheduled method

        verify(executionService).fail(executionId, "Anthropic API call failed: timeout");
        verify(executionService, never()).complete(any(), any(), anyBoolean(), any(), any(), any());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void pollAndProcessOne_runnerThrowsPartialUsageException_recordsTheTokensAlreadySpent() {
        // The live-observed shape: a few rounds succeed and are genuinely
        // billed, then the provider call fails outright (rate limit,
        // insufficient credit, network error) -- that real spend must reach
        // the DB, not just the bare error message.
        UUID tenantId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        AgentExecution job = new AgentExecution();
        job.setId(executionId);
        job.setTenantId(tenantId);
        job.setPrompt("do something");
        job.setAgentType("coding-agent");
        when(executionService.claimNext()).thenReturn(Optional.of(job));
        when(agentPromptRunner.run(any()))
                .thenThrow(new ToolCallingChatEngine.PartialUsageException(
                        "Your credit balance is too low to access the Anthropic API.", null, 900, 150, 1050));

        worker.pollAndProcessOne();

        verify(executionService).fail(executionId, "Your credit balance is too low to access the Anthropic API.", 900, 150, 1050);
        verify(executionService, never()).fail(eq(executionId), anyString());
        assertThat(TenantContext.get()).isNull();
    }

    // ---------- cancellation ----------

    @Test
    void pollAndProcessOne_resultCancelled_marksCancelledNotFailed() {
        // A cancel is what the user asked for, not a failure -- must route
        // to executionService.cancel(), checked BEFORE the incomplete()
        // branch in AgentJobWorker (a cancelled ToolChatResult happens to
        // also carry incomplete==false, but cancelled must win the branch).
        UUID tenantId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        AgentExecution job = queuedJob(executionId);
        job.setTenantId(tenantId);
        when(executionService.claimNext()).thenReturn(Optional.of(job));
        when(agentPromptRunner.run(any())).thenReturn(
                new ToolCallingChatEngine.ToolChatResult(null, true, false, null, 100, 20, 120, true));

        worker.pollAndProcessOne();

        verify(executionService).cancel(executionId, 100, 20, 120);
        verify(executionService, never()).fail(any(), any(), any(), any(), any());
        verify(executionService, never()).fail(any(), any());
        verify(executionService, never()).complete(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void pollAndProcessOne_cancellationCheckPassedToTheRunner_delegatesToExecutionServiceIsCancellationRequested() {
        // AgentPromptRunner/agent-core never query the DB themselves --
        // AgentJobWorker is the one place that builds the actual DB-backed
        // check, since it's the one class that already knows about the row.
        UUID executionId = UUID.randomUUID();
        AgentExecution job = queuedJob(executionId);
        when(executionService.claimNext()).thenReturn(Optional.of(job));
        when(agentPromptRunner.run(any()))
                .thenReturn(new ToolCallingChatEngine.ToolChatResult("done", false, false, null));
        when(executionService.isCancellationRequested(executionId)).thenReturn(true);

        worker.pollAndProcessOne();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentPromptRunner).run(captor.capture());
        assertThat(captor.getValue().cancellationCheck().getAsBoolean()).isTrue();
        verify(executionService).isCancellationRequested(executionId);
    }

    // ---------- heartbeat ownership (see V32__agent_execution_heartbeat.sql) ----------

    @Test
    void pollAndProcessOne_registersTheJobForHeartbeatingBeforeRunningIt() {
        // Ordering matters: if the job were only tracked after the run, a
        // long execution would go unstamped for its whole duration and the
        // reaper would fail it out from under itself.
        UUID executionId = UUID.randomUUID();
        AgentExecution job = queuedJob(executionId);
        when(executionService.claimNext()).thenReturn(Optional.of(job));
        when(agentPromptRunner.run(any()))
                .thenReturn(new ToolCallingChatEngine.ToolChatResult("done", false, false, null));

        worker.pollAndProcessOne();

        InOrder inOrder = inOrder(heartbeatMonitor, agentPromptRunner);
        inOrder.verify(heartbeatMonitor).track(executionId);
        inOrder.verify(agentPromptRunner).run(any());
        inOrder.verify(heartbeatMonitor).untrack(executionId);
    }

    @Test
    void pollAndProcessOne_runFailed_stillStopsHeartbeatingTheJob() {
        // An id left in the in-flight set would be stamped forever, keeping
        // a dead row looking alive to the reaper -- exactly the state the
        // heartbeat exists to detect.
        UUID executionId = UUID.randomUUID();
        when(executionService.claimNext()).thenReturn(Optional.of(queuedJob(executionId)));
        when(agentPromptRunner.run(any())).thenThrow(new RuntimeException("boom"));

        worker.pollAndProcessOne();

        verify(heartbeatMonitor).untrack(executionId);
    }

    @Test
    void pollAndProcessOne_nothingQueued_tracksNothing() {
        when(executionService.claimNext()).thenReturn(Optional.empty());

        worker.pollAndProcessOne();

        verifyNoInteractions(heartbeatMonitor);
    }

    private AgentExecution queuedJob(UUID executionId) {
        AgentExecution job = new AgentExecution();
        job.setId(executionId);
        job.setTenantId(UUID.randomUUID());
        job.setPrompt("do something");
        job.setAgentType("coding-agent");
        return job;
    }
}
