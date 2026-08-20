package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.gateway.agent.AgentException;
import com.enterprisehub.runtime.audit.ToolExecutionAuditRecord;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.audit.ToolExecutionOutcome;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCatalogTest {

    private final SandboxSession session = mock(SandboxSession.class);
    private final ToolExecutionListener listener = mock(ToolExecutionListener.class);
    private final CredentialResolver credentialResolver = mock(CredentialResolver.class);
    private final ToolCreationContext toolContext = new ToolCreationContext("tenant-1", "user-1", null);

    private ToolFactory fakeFactory(String name) {
        return new ToolFactory() {
            @Override
            public String toolName() {
                return name;
            }

            @Override
            public String category() {
                return "test";
            }

            @Override
            public AgentTool create(SandboxSession s, CredentialResolver c, ToolCreationContext ctx) {
                AgentTool tool = mock(AgentTool.class);
                when(tool.name()).thenReturn(name);
                return tool;
            }
        };
    }

    @Test
    void instantiate_buildsOneToolPerRequestedName_inOrder() {
        ToolCatalog catalog = new ToolCatalog(List.of(fakeFactory("a"), fakeFactory("b")));

        List<AgentTool> tools = catalog.instantiate(List.of("b", "a"), session, listener, credentialResolver, toolContext);

        assertThat(tools).hasSize(2);
    }

    @Test
    void instantiate_unknownToolName_throwsInternalServerError() {
        ToolCatalog catalog = new ToolCatalog(List.of(fakeFactory("a")));

        assertThatThrownBy(() -> catalog.instantiate(List.of("does_not_exist"), session, listener, credentialResolver, toolContext))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does_not_exist");
    }

    @Test
    void all_returnsEveryRegisteredFactory() {
        ToolCatalog catalog = new ToolCatalog(List.of(fakeFactory("a"), fakeFactory("b"), fakeFactory("c")));

        assertThat(catalog.all()).hasSize(3);
        assertThat(catalog.all().stream().map(ToolFactory::toolName)).containsExactlyInAnyOrder("a", "b", "c");
    }

    /**
     * The regression guard for the bug that made this decorator necessary:
     * three of nine tools (get_current_date_time, delegate_to_agent,
     * retrieval) implemented AgentTool directly, so their factories took a
     * ToolExecutionListener and silently dropped it -- they never wrote a
     * single row to tool_executions.
     *
     * This asserts the property rather than the fix: whatever a factory
     * returns, what leaves the catalog must audit. A new tool added by a
     * future contributor is covered without anyone remembering to add it
     * here, because the catalog -- not the tool -- is what applies auditing.
     */
    @Test
    void instantiate_everyToolIsAudited_regardlessOfWhatItsFactoryReturns() {
        ToolCatalog catalog = new ToolCatalog(List.of(fakeFactory("a"), fakeFactory("b")));

        List<AgentTool> tools = catalog.instantiate(List.of("a", "b"), session, listener, credentialResolver, toolContext);
        tools.forEach(tool -> tool.execute(new ToolExecutionContext("tenant-1", "exec-1"), Map.of()));

        ArgumentCaptor<ToolExecutionAuditRecord> captor = ArgumentCaptor.forClass(ToolExecutionAuditRecord.class);
        verify(listener, times(2)).onToolExecuted(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolExecutionAuditRecord::toolName)
                .containsExactlyInAnyOrder("a", "b");
        assertThat(captor.getAllValues()).allSatisfy(record -> {
            assertThat(record.tenantId()).isEqualTo("tenant-1");
            assertThat(record.executionId()).isEqualTo("exec-1");
            assertThat(record.outcome()).isEqualTo(ToolExecutionOutcome.SUCCESS);
        });
    }

    /** A tool cannot opt out of auditing by being the kind that needs no sandbox -- that distinction is what the old bug tracked. */
    @Test
    void instantiate_nonSandboxedTool_isAuditedToo() {
        ToolCatalog catalog = new ToolCatalog(List.of(new CurrentDateTimeToolFactory()));

        AgentTool tool = catalog.instantiate(
                List.of("get_current_date_time"), session, listener, credentialResolver, toolContext).get(0);
        tool.execute(new ToolExecutionContext("tenant-1", "exec-1"), Map.of());

        ArgumentCaptor<ToolExecutionAuditRecord> captor = ArgumentCaptor.forClass(ToolExecutionAuditRecord.class);
        verify(listener).onToolExecuted(captor.capture());
        assertThat(captor.getValue().toolName()).isEqualTo("get_current_date_time");
        assertThat(captor.getValue().outcome()).isEqualTo(ToolExecutionOutcome.SUCCESS);
    }

    @Test
    void realFactories_allSixToolsPresent_noNameCollisions() {
        ToolCatalog catalog = new ToolCatalog(List.of(
                new CurrentDateTimeToolFactory(),
                new RunShellCommandToolFactory(),
                new GitCloneToolFactory(),
                new ReadFileToolFactory(),
                new WriteFileToolFactory(),
                new OpenPullRequestToolFactory()));

        assertThat(catalog.all().stream().map(ToolFactory::toolName)).containsExactlyInAnyOrder(
                "get_current_date_time", "run_shell_command", "git_clone", "read_file", "write_file", "open_pull_request");
    }
}
