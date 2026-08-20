package com.enterprisehub.runtime.audit;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Auditing used to live in AbstractSandboxedTool, so it only covered tools
 * that extended it -- get_current_date_time, delegate_to_agent and
 * retrieval implemented AgentTool directly and were never audited at all.
 * These tests pin the behaviour in its new, tool-agnostic home.
 */
class AuditingToolTest {

    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("tenant-1", "exec-1");

    private ToolExecutionListener listener;

    @BeforeEach
    void setUp() {
        listener = mock(ToolExecutionListener.class);
    }

    /** A plain AgentTool that extends nothing -- exactly the shape that was silently unaudited. */
    private static AgentTool tool(String name, RuntimeException toThrow) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "a test tool";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of("arg", "an argument");
            }

            @Override
            public Set<String> optionalParameterNames() {
                return Set.of("arg");
            }

            @Override
            public boolean isTerminalSuccess(String result) {
                return "done".equals(result);
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                if (toThrow != null) {
                    throw toThrow;
                }
                return "done";
            }
        };
    }

    @Test
    void execute_success_emitsOneSuccessRecordCarryingTenantExecutionAndToolName() {
        new AuditingTool(tool("get_current_date_time", null), listener).execute(CONTEXT, Map.of());

        ArgumentCaptor<ToolExecutionAuditRecord> captor = ArgumentCaptor.forClass(ToolExecutionAuditRecord.class);
        verify(listener).onToolExecuted(captor.capture());
        ToolExecutionAuditRecord record = captor.getValue();
        assertThat(record.tenantId()).isEqualTo("tenant-1");
        assertThat(record.executionId()).isEqualTo("exec-1");
        assertThat(record.toolName()).isEqualTo("get_current_date_time");
        assertThat(record.outcome()).isEqualTo(ToolExecutionOutcome.SUCCESS);
        assertThat(record.errorMessage()).isNull();
    }

    @Test
    void execute_failure_emitsFailureRecordAndRethrows() {
        AgentTool failing = tool("delegate_to_agent", new IllegalStateException("boom"));

        assertThatThrownBy(() -> new AuditingTool(failing, listener).execute(CONTEXT, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        ArgumentCaptor<ToolExecutionAuditRecord> captor = ArgumentCaptor.forClass(ToolExecutionAuditRecord.class);
        verify(listener).onToolExecuted(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(ToolExecutionOutcome.FAILURE);
        assertThat(captor.getValue().errorMessage()).isEqualTo("boom");
    }

    /**
     * open_pull_request ends the tool-calling loop through isTerminalSuccess.
     * A decorator answering on the delegate's behalf would turn every
     * terminal tool into a non-terminal one and let runs continue past
     * their natural end.
     */
    @Test
    void isTerminalSuccess_isDelegated_notDefaulted() {
        AuditingTool audited = new AuditingTool(tool("open_pull_request", null), listener);

        assertThat(audited.isTerminalSuccess("done")).isTrue();
        assertThat(audited.isTerminalSuccess("not-done")).isFalse();
    }

    @Test
    void llmFacingMetadata_isDelegated_soWrappingIsInvisibleToTheModel() {
        AuditingTool audited = new AuditingTool(tool("retrieval", null), listener);

        assertThat(audited.name()).isEqualTo("retrieval");
        assertThat(audited.description()).isEqualTo("a test tool");
        assertThat(audited.parameterDescriptions()).containsKey("arg");
        assertThat(audited.optionalParameterNames()).containsExactly("arg");
    }
}
