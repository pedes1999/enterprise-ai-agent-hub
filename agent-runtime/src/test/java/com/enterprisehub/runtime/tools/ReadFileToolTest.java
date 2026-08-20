package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReadFileToolTest {

    private SandboxClient sandboxClient;
    private ToolExecutionListener listener;
    private ReadFileTool tool;
    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("tenant-1", "exec-1");

    @BeforeEach
    void setUp() {
        sandboxClient = mock(SandboxClient.class);
        listener = mock(ToolExecutionListener.class);
        tool = new ReadFileTool(sandboxClient);
    }

    @Test
    void execute_readsFileRelativeToWorkspaceRoot() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.readFile(any(), eq("/tmp/workspace/repo/README.md")))
                .thenReturn("# Hello World".getBytes(StandardCharsets.UTF_8));

        String result = tool.execute(CONTEXT, Map.of("path", "README.md"));

        assertThat(result).isEqualTo("# Hello World");
    }

    @Test
    void execute_nestedPath_resolvedUnderWorkspaceRoot() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.readFile(any(), eq("/tmp/workspace/repo/src/main/App.java")))
                .thenReturn("class App {}".getBytes(StandardCharsets.UTF_8));

        String result = tool.execute(CONTEXT, Map.of("path", "src/main/App.java"));

        assertThat(result).isEqualTo("class App {}");
    }

    @Test
    void execute_absolutePath_rejected() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("path", "/etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_pathTraversal_rejected() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("path", "../../etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_blankPath_rejected() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("path", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_missingPathArgument_rejected() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_oversizedFile_truncated() {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        byte[] huge = new byte[128 * 1024];
        when(sandboxClient.readFile(any(), any())).thenReturn(huge);

        String result = tool.execute(CONTEXT, Map.of("path", "big.txt"));

        assertThat(result.getBytes(StandardCharsets.UTF_8).length).isEqualTo(64 * 1024);
    }

    @Test
    void execute_sandboxDestroyedAfterRead() {
        SandboxHandle handle = new SandboxHandle("s1");
        when(sandboxClient.create(any())).thenReturn(handle);
        when(sandboxClient.readFile(any(), any())).thenReturn("x".getBytes());

        tool.execute(CONTEXT, Map.of("path", "a.txt"));

        verify(sandboxClient).destroy(handle);
    }

    @Test
    void nameAndDescription_areNonEmpty() {
        assertThat(tool.name()).isEqualTo("read_file");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.parameterDescriptions()).containsKey("path");
    }
}
