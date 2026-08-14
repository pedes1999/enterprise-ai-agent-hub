package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchCodeToolTest {

    private SandboxClient sandboxClient;
    private ToolExecutionListener listener;
    private SearchCodeTool tool;
    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("tenant-1", "exec-1");

    @BeforeEach
    void setUp() {
        sandboxClient = mock(SandboxClient.class);
        listener = mock(ToolExecutionListener.class);
        tool = new SearchCodeTool(sandboxClient, listener);
    }

    @Test
    void execute_buildsAGrepCommandFromTheSharedWorkspace() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "src/App.java:12:foo()", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("pattern", "foo"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue())
                .contains("mkdir -p /tmp/workspace/repo")
                .contains("cd /tmp/workspace/repo")
                .contains("grep -rnI")
                .contains("'foo'");
    }

    @Test
    void execute_filePatternGiven_addsIncludeFlag() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("pattern", "foo", "filePattern", "*.java"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).contains("--include='*.java'");
    }

    @Test
    void execute_filePatternOmitted_noIncludeFlag() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("pattern", "foo"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).doesNotContain("--include");
    }

    @Test
    void execute_patternWithEmbeddedSingleQuote_shellEscapedNotBroken() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("pattern", "it's a test"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).contains("'it'\\''s a test'");
    }

    @Test
    void execute_noMatches_reportsNoMatchesRatherThanEmptyString() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(1, "", "", false, Duration.ZERO));

        String result = tool.execute(CONTEXT, Map.of("pattern", "nonexistent"));

        assertThat(result).isEqualTo("No matches.");
    }

    @Test
    void execute_matchesFound_returnsRawGrepOutput() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "a.java:1:foo\nb.java:5:foo", "", false, Duration.ZERO));

        String result = tool.execute(CONTEXT, Map.of("pattern", "foo"));

        assertThat(result).isEqualTo("a.java:1:foo\nb.java:5:foo");
    }

    @Test
    void execute_truncatedOutput_notedInResult() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new CommandResult(0, "partial", "", true, Duration.ZERO));

        String result = tool.execute(CONTEXT, Map.of("pattern", "foo"));

        assertThat(result).contains("truncated");
    }

    @Test
    void execute_blankPattern_throwsWithoutTouchingSandbox() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("pattern", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_missingPatternArgument_throws() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nameAndDescription_areNonEmpty_forLlmToolSpecification() {
        assertThat(tool.name()).isEqualTo("search_code");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.parameterDescriptions()).containsKeys("pattern", "filePattern");
        assertThat(tool.optionalParameterNames()).containsExactly("filePattern");
    }
}
