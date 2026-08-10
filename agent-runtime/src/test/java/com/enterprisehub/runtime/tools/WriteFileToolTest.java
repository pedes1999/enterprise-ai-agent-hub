package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WriteFileToolTest {

    private SandboxClient sandboxClient;
    private ToolExecutionListener listener;
    private WriteFileTool tool;
    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("tenant-1", "exec-1");

    @BeforeEach
    void setUp() {
        sandboxClient = mock(SandboxClient.class);
        listener = mock(ToolExecutionListener.class);
        tool = new WriteFileTool(sandboxClient, listener);
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
    }

    @Test
    void execute_writesFileRelativeToWorkspaceRoot() {
        String result = tool.execute(CONTEXT, Map.of("path", "README.md", "content", "# Hello"));

        assertThat(result).contains("Wrote").contains("README.md");
        verify(sandboxClient).writeFile(any(), eq("/tmp/workspace/repo/README.md"), eq("# Hello".getBytes()));
    }

    @Test
    void execute_createsParentDirectoryFirst() {
        tool.execute(CONTEXT, Map.of("path", "src/main/App.java", "content", "class App {}"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).contains("mkdir -p").contains("/tmp/workspace/repo/src/main");
    }

    @Test
    void execute_absolutePath_rejected() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("path", "/etc/passwd", "content", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_pathTraversal_rejected() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("path", "../outside.txt", "content", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_missingContentArgument_rejected() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("path", "a.txt")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_oversizedContent_rejectedBeforeTouchingSandbox() {
        String huge = "x".repeat(300 * 1024);
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("path", "big.txt", "content", huge)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_sandboxDestroyedAfterWrite() {
        SandboxHandle handle = new SandboxHandle("s1");
        when(sandboxClient.create(any())).thenReturn(handle);

        tool.execute(CONTEXT, Map.of("path", "a.txt", "content", "x"));

        verify(sandboxClient).destroy(handle);
    }

    @Test
    void nameAndDescription_areNonEmpty() {
        assertThat(tool.name()).isEqualTo("write_file");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.parameterDescriptions()).containsKeys("path", "content");
    }
}
