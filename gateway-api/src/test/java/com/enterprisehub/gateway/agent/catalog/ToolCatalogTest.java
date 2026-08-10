package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.gateway.agent.AgentException;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ToolCatalogTest {

    private final SandboxSession session = mock(SandboxSession.class);
    private final ToolExecutionListener listener = mock(ToolExecutionListener.class);
    private final CredentialResolver credentialResolver = mock(CredentialResolver.class);

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
            public AgentTool create(SandboxSession s, ToolExecutionListener l, CredentialResolver c) {
                return mock(AgentTool.class);
            }
        };
    }

    @Test
    void instantiate_buildsOneToolPerRequestedName_inOrder() {
        ToolCatalog catalog = new ToolCatalog(List.of(fakeFactory("a"), fakeFactory("b")));

        List<AgentTool> tools = catalog.instantiate(List.of("b", "a"), session, listener, credentialResolver);

        assertThat(tools).hasSize(2);
    }

    @Test
    void instantiate_unknownToolName_throwsInternalServerError() {
        ToolCatalog catalog = new ToolCatalog(List.of(fakeFactory("a")));

        assertThatThrownBy(() -> catalog.instantiate(List.of("does_not_exist"), session, listener, credentialResolver))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does_not_exist");
    }

    @Test
    void all_returnsEveryRegisteredFactory() {
        ToolCatalog catalog = new ToolCatalog(List.of(fakeFactory("a"), fakeFactory("b"), fakeFactory("c")));

        assertThat(catalog.all()).hasSize(3);
        assertThat(catalog.all().stream().map(ToolFactory::toolName)).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void realFactories_allFiveToolsPresent_noNameCollisions() {
        ToolCatalog catalog = new ToolCatalog(List.of(
                new CurrentDateTimeToolFactory(),
                new RunShellCommandToolFactory(),
                new GitCloneToolFactory(),
                new ReadFileToolFactory(),
                new WriteFileToolFactory()));

        assertThat(catalog.all().stream().map(ToolFactory::toolName)).containsExactlyInAnyOrder(
                "get_current_date_time", "run_shell_command", "git_clone", "read_file", "write_file");
    }
}
