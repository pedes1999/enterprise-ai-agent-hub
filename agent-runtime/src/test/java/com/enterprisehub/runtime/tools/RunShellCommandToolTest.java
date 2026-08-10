package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionAuditRecord;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.audit.ToolExecutionOutcome;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import com.enterprisehub.runtime.sandbox.SandboxSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RunShellCommandToolTest {

    private SandboxClient sandboxClient;
    private ToolExecutionListener listener;
    private RunShellCommandTool tool;
    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("tenant-1", "exec-1");

    @BeforeEach
    void setUp() {
        sandboxClient = mock(SandboxClient.class);
        listener = mock(ToolExecutionListener.class);
        tool = new RunShellCommandTool(sandboxClient, listener);
    }

    @Test
    void execute_runsCommandInAFreshSandbox_andDestroysIt() {
        SandboxHandle handle = new SandboxHandle("sandbox-1");
        when(sandboxClient.create(any())).thenReturn(handle);
        when(sandboxClient.runCommand(eq(handle), any(), any()))
                .thenReturn(new CommandResult(0, "file1\nfile2", "", false, Duration.ofMillis(200)));

        String result = tool.execute(CONTEXT, Map.of("command", "ls -la"));

        assertThat(result).contains("exit_code: 0").contains("file1\nfile2");
        verify(sandboxClient).create(any());
        verify(sandboxClient).destroy(handle);
    }

    @Test
    void execute_runsFromTheSharedWorkspaceDirectory() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("command", "ls -la"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue())
                .contains("mkdir -p /tmp/workspace/repo")
                .contains("cd /tmp/workspace/repo")
                .endsWith("ls -la");
    }

    @Test
    void execute_sandboxSpecCarriesTenantAndExecutionIdFromContext() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("command", "echo hi"));

        ArgumentCaptor<SandboxSpec> specCaptor = ArgumentCaptor.forClass(SandboxSpec.class);
        verify(sandboxClient).create(specCaptor.capture());
        assertThat(specCaptor.getValue().tenantId()).isEqualTo("tenant-1");
        assertThat(specCaptor.getValue().executionId()).isEqualTo("exec-1");
    }

    @Test
    void execute_noCredentialsInjected_thisToolDoesntNeedAny() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("command", "echo hi"));

        ArgumentCaptor<SandboxSpec> specCaptor = ArgumentCaptor.forClass(SandboxSpec.class);
        verify(sandboxClient).create(specCaptor.capture());
        assertThat(specCaptor.getValue().credentials()).isEmpty();
    }

    @Test
    void execute_nonZeroExitCode_isNotAnException_justReportedInResult() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(127, "", "command not found", false, Duration.ZERO));

        String result = tool.execute(CONTEXT, Map.of("command", "not-a-real-command"));

        assertThat(result).contains("exit_code: 127").contains("stderr:\ncommand not found");
    }

    @Test
    void execute_truncatedOutput_notedInResult() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "partial output", "", true, Duration.ZERO));

        String result = tool.execute(CONTEXT, Map.of("command", "cat huge-file.txt"));

        assertThat(result).contains("truncated");
    }

    @Test
    void execute_blankCommand_throwsWithoutTouchingSandbox() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("command", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_missingCommandArgument_throws() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_sandboxCreationFails_stillDestroysNothingButPropagates_andAudited() {
        when(sandboxClient.create(any())).thenThrow(new com.enterprisehub.runtime.sandbox.SandboxException("sidecar unreachable"));

        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("command", "ls")))
                .isInstanceOf(com.enterprisehub.runtime.sandbox.SandboxException.class);

        verify(sandboxClient, never()).destroy(any());
        ArgumentCaptor<ToolExecutionAuditRecord> auditCaptor = ArgumentCaptor.forClass(ToolExecutionAuditRecord.class);
        verify(listener).onToolExecuted(auditCaptor.capture());
        assertThat(auditCaptor.getValue().outcome()).isEqualTo(ToolExecutionOutcome.FAILURE);
    }

    @Test
    void execute_commandFailsWithinSandbox_sandboxStillDestroyed() {
        SandboxHandle handle = new SandboxHandle("s1");
        when(sandboxClient.create(any())).thenReturn(handle);
        when(sandboxClient.runCommand(any(), any(), any())).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("command", "sleep 999")));

        verify(sandboxClient).destroy(handle);
    }

    // ---------- audit logging (via the AbstractSandboxedTool it extends) ----------

    @Test
    void execute_success_auditsWithSuccessOutcomeAndToolName() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "ok", "", false, Duration.ofMillis(50)));

        tool.execute(CONTEXT, Map.of("command", "echo ok"));

        ArgumentCaptor<ToolExecutionAuditRecord> captor = ArgumentCaptor.forClass(ToolExecutionAuditRecord.class);
        verify(listener).onToolExecuted(captor.capture());
        ToolExecutionAuditRecord record = captor.getValue();
        assertThat(record.tenantId()).isEqualTo("tenant-1");
        assertThat(record.executionId()).isEqualTo("exec-1");
        assertThat(record.toolName()).isEqualTo("run_shell_command");
        assertThat(record.outcome()).isEqualTo(ToolExecutionOutcome.SUCCESS);
        assertThat(record.errorMessage()).isNull();
    }

    @Test
    void execute_failure_auditsWithErrorMessage() {
        when(sandboxClient.create(any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("command", "ls")));

        ArgumentCaptor<ToolExecutionAuditRecord> captor = ArgumentCaptor.forClass(ToolExecutionAuditRecord.class);
        verify(listener).onToolExecuted(captor.capture());
        assertThat(captor.getValue().errorMessage()).isEqualTo("boom");
    }

    @Test
    void nameAndDescription_areNonEmpty_forLlmToolSpecification() {
        assertThat(tool.name()).isEqualTo("run_shell_command");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.parameterDescriptions()).containsKey("command");
    }
}
