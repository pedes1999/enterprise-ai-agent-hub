package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import com.enterprisehub.runtime.sandbox.SandboxSpec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Writes (creating or overwriting) a file in the shared workspace --
 * meant to follow a git_clone + read_file within the same execution, so an
 * agent can actually make a code change, not just inspect one. Parent
 * directories are created first via a shell command, same lesson as
 * GitCloneTool's original mkdir bugfix -- never assumed to already exist.
 */
public class WriteFileTool extends AbstractSandboxedTool {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SANDBOX_MAX_LIFETIME = Duration.ofMinutes(2);
    private static final long MAX_CONTENT_BYTES = 256 * 1024;

    public WriteFileTool(SandboxClient sandboxClient, ToolExecutionListener listener) {
        super(sandboxClient, listener);
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Writes content to a file in the shared workspace (the same directory git_clone clones into), "
                + "creating it (and any needed parent directories) if it doesn't exist, or overwriting it if it "
                + "does. Provide a path relative to the repository root, e.g. 'README.md' or 'src/main/App.java'.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of(
                "path", "Path to the file, relative to the repository root, e.g. 'README.md'.",
                "content", "The full content to write to the file. This replaces the entire file if it already exists.");
    }

    @Override
    protected String doExecute(ToolExecutionContext context, Map<String, String> arguments) {
        String path = WorkspacePath.resolve(arguments.get("path"));
        String content = arguments.get("content");
        if (content == null) {
            throw new IllegalArgumentException("content argument is required");
        }
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "content is " + contentBytes.length + " bytes, exceeding the " + MAX_CONTENT_BYTES + " byte limit");
        }

        SandboxSpec spec = new SandboxSpec(
                context.tenantId(), context.executionId(),
                Map.of(), SANDBOX_MAX_LIFETIME, MAX_CONTENT_BYTES);

        withSandbox(spec, handle -> {
            createParentDirectory(handle, path);
            sandboxClient.writeFile(handle, path, contentBytes);
            return null;
        });

        return "Wrote " + contentBytes.length + " bytes to " + path + ".";
    }

    private void createParentDirectory(SandboxHandle handle, String path) {
        int lastSlash = path.lastIndexOf('/');
        String parentDir = lastSlash <= 0 ? "/" : path.substring(0, lastSlash);
        sandboxClient.runCommand(handle, "mkdir -p '" + parentDir.replace("'", "'\\''") + "'", COMMAND_TIMEOUT);
    }
}
