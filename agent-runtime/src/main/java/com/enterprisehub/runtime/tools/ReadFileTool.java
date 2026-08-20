package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import com.enterprisehub.runtime.sandbox.SandboxSpec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

/**
 * Reads a file from the shared workspace -- meant to be used after
 * git_clone within the same execution (see Workspace/SandboxSession), so
 * an agent can inspect a file it just cloned. Works standalone too (each
 * call still goes through withSandbox, which reuses the session's sandbox
 * when one exists, or provisions its own otherwise -- see AbstractSandboxedTool).
 */
public class ReadFileTool extends AbstractSandboxedTool {

    private static final Duration SANDBOX_MAX_LIFETIME = Duration.ofMinutes(2);
    private static final long MAX_OUTPUT_BYTES = 64 * 1024;

    public ReadFileTool(SandboxClient sandboxClient) {
        super(sandboxClient);
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Reads a file's contents from the shared workspace (the same directory git_clone clones into). "
                + "Provide a path relative to the repository root, e.g. 'README.md' or 'src/main/App.java'.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of("path", "Path to the file, relative to the repository root, e.g. 'README.md'.");
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        String path = WorkspacePath.resolve(arguments.get("path"));

        SandboxSpec spec = new SandboxSpec(
                context.tenantId(), context.executionId(),
                Map.of(), SANDBOX_MAX_LIFETIME, MAX_OUTPUT_BYTES);

        byte[] content = withSandbox(spec, handle -> readAndTruncate(handle, path));
        return new String(content, StandardCharsets.UTF_8);
    }

    private byte[] readAndTruncate(SandboxHandle handle, String path) {
        byte[] content = sandboxClient.readFile(handle, path);
        if (content.length <= MAX_OUTPUT_BYTES) {
            return content;
        }
        return Arrays.copyOf(content, (int) MAX_OUTPUT_BYTES);
    }
}
