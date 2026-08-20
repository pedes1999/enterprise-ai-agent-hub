package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxSpec;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Greps the shared workspace instead of reading whole files -- a much
 * cheaper way for a model to locate the code it actually needs before
 * spending tokens on read_file. Not a new CAPABILITY (run_shell_command
 * can already run grep directly, since it's a generic, unrestricted shell
 * executor -- see its own javadoc), just a cheaper, single-purpose
 * interface a model reaches for instead of exploring blind with
 * read_file, with output already shaped as file:line:match instead of
 * full file contents.
 *
 * Built on `grep`, not `rg` (ripgrep) -- the sandbox sidecar's image
 * (agent-runtime/sidecar/Dockerfile, node:20-slim) ships grep as part of
 * the base Debian image but does not install ripgrep.
 */
public class SearchCodeTool extends AbstractSandboxedTool {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SANDBOX_MAX_LIFETIME = Duration.ofMinutes(2);
    private static final long MAX_OUTPUT_BYTES = 64 * 1024;

    public SearchCodeTool(SandboxClient sandboxClient) {
        super(sandboxClient);
    }

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String description() {
        return "Searches the shared workspace (the same directory git_clone clones into) for a text pattern, "
                + "returning matching lines as file:line:match. Prefer this over read_file when you don't yet "
                + "know which file or line you need -- much cheaper than reading whole files to find something.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of(
                "pattern", "The text pattern to search for (a basic regular expression, as grep understands it).",
                "filePattern", "Optional -- restrict the search to files matching this glob, e.g. '*.java'.");
    }

    @Override
    public Set<String> optionalParameterNames() {
        return Set.of("filePattern");
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        String pattern = arguments.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern argument is required");
        }
        String filePattern = arguments.get("filePattern");

        SandboxSpec spec = new SandboxSpec(
                context.tenantId(), context.executionId(),
                Map.of(), SANDBOX_MAX_LIFETIME, MAX_OUTPUT_BYTES);

        String grepCommand = buildGrepCommand(pattern, filePattern);
        String commandInWorkspace = "mkdir -p " + Workspace.ROOT + " && cd " + Workspace.ROOT + " && " + grepCommand;
        CommandResult result = withSandbox(spec, handle -> sandboxClient.runCommand(handle, commandInWorkspace, COMMAND_TIMEOUT));

        return formatResult(result);
    }

    /**
     * -n for line numbers, -r to recurse, -I to skip binary files (a
     * cloned repo's build output/artifacts shouldn't produce garbage
     * matches). --include is only added when filePattern was given --
     * grep treats an absent flag as "every file", not "no files".
     * Single-quoted with embedded single quotes escaped the standard
     * shell way ('\'') -- this trusts the model's shell-string content no
     * more and no less than run_shell_command already does for its own
     * `command` argument, since both ultimately execute inside the same
     * sandboxed shell.
     */
    private String buildGrepCommand(String pattern, String filePattern) {
        StringBuilder command = new StringBuilder("grep -rnI");
        if (filePattern != null && !filePattern.isBlank()) {
            command.append(" --include=").append(shellQuote(filePattern));
        }
        command.append(" -- ").append(shellQuote(pattern)).append(" .");
        // grep exits 1 (not an error here) when nothing matches -- `|| true`
        // keeps that from being confused with a real command failure, and
        // still leaves stdout ("no output") as the honest "no matches" signal.
        command.append(" || true");
        return command.toString();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String formatResult(CommandResult result) {
        StringBuilder output = new StringBuilder();
        if (result.outputTruncated()) {
            output.append("(output truncated to the configured limit)\n");
        }
        if (result.stdout() == null || result.stdout().isBlank()) {
            output.append("No matches.");
        } else {
            output.append(result.stdout());
        }
        if (result.stderr() != null && !result.stderr().isBlank()) {
            output.append("\nstderr:\n").append(result.stderr());
        }
        return output.toString();
    }
}
