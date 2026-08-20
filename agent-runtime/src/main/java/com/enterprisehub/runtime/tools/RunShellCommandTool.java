package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxSpec;

import java.time.Duration;
import java.util.Map;

/**
 * The first real (sandboxed) tool. Runs from the shared Workspace.ROOT
 * directory (created first if missing, so this works whether or not
 * GitCloneTool has run yet in this execution) -- when a SandboxSession
 * makes the sandbox persist across the whole execution (see its javadoc),
 * this is what lets a command like "mvn test" or "ls" naturally see
 * whatever GitCloneTool cloned earlier in the same run. Without
 * SandboxSession (e.g. this tool used standalone), each invocation still
 * gets its own fresh sandbox as before -- only the working directory
 * changed, not the tool's own lifecycle.
 */
public class RunShellCommandTool extends AbstractSandboxedTool {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SANDBOX_MAX_LIFETIME = Duration.ofMinutes(2);
    private static final long MAX_OUTPUT_BYTES = 64 * 1024;

    public RunShellCommandTool(SandboxClient sandboxClient) {
        super(sandboxClient);
    }

    @Override
    public String name() {
        return "run_shell_command";
    }

    @Override
    public String description() {
        return "Runs a single shell command inside an isolated sandbox, from the shared workspace directory "
                + "(the same one git_clone clones into, if it's been used earlier in this run), and returns its "
                + "exit code, stdout, and stderr. Use for inspecting a repository, running a build, or any other "
                + "terminal operation.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of("command", "The shell command to execute, e.g. 'ls -la' or 'mvn test'.");
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        String command = arguments.get("command");
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command argument is required");
        }

        SandboxSpec spec = new SandboxSpec(
                context.tenantId(), context.executionId(),
                Map.of(), SANDBOX_MAX_LIFETIME, MAX_OUTPUT_BYTES);

        String commandInWorkspace = "mkdir -p " + Workspace.ROOT + " && cd " + Workspace.ROOT + " && " + command;
        CommandResult result = withSandbox(spec, handle -> sandboxClient.runCommand(handle, commandInWorkspace, COMMAND_TIMEOUT));

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
