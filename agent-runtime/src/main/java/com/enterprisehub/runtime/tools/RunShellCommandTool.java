package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxSpec;

import java.time.Duration;
import java.util.Map;

/**
 * The first real (sandboxed) tool. Every invocation gets a fresh, empty
 * sandbox with no credentials injected -- this tool doesn't need any (it
 * has no notion of "which repo"); a future git-aware tool is where
 * CredentialResolver actually gets used.
 */
public class RunShellCommandTool extends AbstractSandboxedTool {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SANDBOX_MAX_LIFETIME = Duration.ofMinutes(2);
    private static final long MAX_OUTPUT_BYTES = 64 * 1024;

    public RunShellCommandTool(SandboxClient sandboxClient, ToolExecutionListener listener) {
        super(sandboxClient, listener);
    }

    @Override
    public String name() {
        return "run_shell_command";
    }

    @Override
    public String description() {
        return "Runs a single shell command inside an isolated, ephemeral sandbox and returns its exit code, "
                + "stdout, and stderr. Use for inspecting a repository, running a build, or any other terminal "
                + "operation. Every invocation gets a fresh sandbox that is destroyed immediately after.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of("command", "The shell command to execute, e.g. 'ls -la' or 'mvn test'.");
    }

    @Override
    protected String doExecute(ToolExecutionContext context, Map<String, String> arguments) {
        String command = arguments.get("command");
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command argument is required");
        }

        SandboxSpec spec = new SandboxSpec(
                context.tenantId(), context.executionId(),
                Map.of(), SANDBOX_MAX_LIFETIME, MAX_OUTPUT_BYTES);

        CommandResult result = withSandbox(spec, handle -> sandboxClient.runCommand(handle, command, COMMAND_TIMEOUT));

        return formatResult(result);
    }

    private String formatResult(CommandResult result) {
        StringBuilder output = new StringBuilder();
        output.append("exit_code: ").append(result.exitCode()).append('\n');
        if (result.outputTruncated()) {
            output.append("(output truncated to the configured limit)\n");
        }
        output.append("stdout:\n").append(result.stdout());
        if (result.stderr() != null && !result.stderr().isBlank()) {
            output.append("\nstderr:\n").append(result.stderr());
        }
        return output.toString();
    }
}
