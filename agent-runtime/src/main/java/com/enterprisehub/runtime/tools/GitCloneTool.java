package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxSpec;

import java.time.Duration;
import java.util.Map;

/**
 * First git-aware tool, and the first to actually use CredentialResolver
 * (RunShellCommandTool needs none). Runs `git clone` INSIDE the sandbox via
 * SandboxClient, exactly like RunShellCommandTool -- never in-process
 * (JGit) on our own host. See this module's pom.xml for why that
 * distinction matters.
 *
 * Only HTTPS repository URLs are accepted (no ssh://, no file://, no
 * relative-looking values) -- keeps the credential story to a single HTTP
 * auth header and avoids a whole class of ssh-agent/known_hosts complexity
 * this doesn't need yet.
 */
public class GitCloneTool extends AbstractSandboxedTool {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SANDBOX_MAX_LIFETIME = Duration.ofMinutes(3);
    private static final long MAX_OUTPUT_BYTES = 64 * 1024;
    private static final String CLONE_TARGET_DIR = Workspace.ROOT;
    private static final String GIT_CREDENTIAL_KIND = "GIT";
    private static final String GIT_TOKEN_ENV_VAR = "GIT_TOKEN";

    private final CredentialResolver credentialResolver;

    public GitCloneTool(SandboxClient sandboxClient, ToolExecutionListener listener, CredentialResolver credentialResolver) {
        super(sandboxClient, listener);
        this.credentialResolver = credentialResolver;
    }

    @Override
    public String name() {
        return "git_clone";
    }

    @Override
    public String description() {
        return "Clones a git repository into the sandbox at " + CLONE_TARGET_DIR + ". Use this before reading or "
                + "modifying files in a repository. Only HTTPS repository URLs are supported.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of("repositoryUrl", "HTTPS URL of the repository to clone, e.g. https://github.com/org/repo.git");
    }

    @Override
    protected String doExecute(ToolExecutionContext context, Map<String, String> arguments) {
        String repositoryUrl = arguments.get("repositoryUrl");
        validateRepositoryUrl(repositoryUrl);

        Map<String, String> credentials = credentialResolver.resolve(context.tenantId(), GIT_CREDENTIAL_KIND);
        String command = buildCloneCommand(repositoryUrl, credentials.containsKey(GIT_TOKEN_ENV_VAR));

        SandboxSpec spec = new SandboxSpec(
                context.tenantId(), context.executionId(),
                credentials, SANDBOX_MAX_LIFETIME, MAX_OUTPUT_BYTES);

        CommandResult result = withSandbox(spec, handle -> sandboxClient.runCommand(handle, command, COMMAND_TIMEOUT));

        return formatResult(result);
    }

    private void validateRepositoryUrl(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new IllegalArgumentException("repositoryUrl argument is required");
        }
        if (!repositoryUrl.startsWith("https://")) {
            throw new IllegalArgumentException("repositoryUrl must be an https:// URL");
        }
        if (repositoryUrl.startsWith("-")) {
            // Defense against a known class of git argument-injection issue
            // where a value starting with '-' gets interpreted as a flag.
            throw new IllegalArgumentException("repositoryUrl must not start with '-'");
        }
    }

    private String buildCloneCommand(String repositoryUrl, boolean hasCredential) {
        // /workspace does not exist by default in a fresh E2B sandbox --
        // discovered by an actual failed clone ("could not create leading
        // directories... Permission denied") during live verification, not
        // assumed. mkdir -p first so a real clone failure (bad URL, auth,
        // network) is what surfaces, not a filesystem setup issue.
        String mkdirPrefix = "mkdir -p " + ShellQuoting.quote(parentDirOf(CLONE_TARGET_DIR)) + " && ";
        String quotedUrl = ShellQuoting.quote(repositoryUrl);
        if (hasCredential) {
            // -c credential.helper=... on the command line applies to this
            // invocation only -- unlike embedding a token directly in the
            // clone URL, it is NOT written into the cloned repo's own
            // .git/config. The sandbox is destroyed right after this one
            // tool call regardless, but this avoids the credential
            // persisting on disk for even that short window.
            //
            // Deliberately NOT `-c http.extraHeader=...` -- found via live
            // testing that some git/curl combinations still fall through to
            // interactive username/password negotiation ("could not read
            // Username for 'https://github.com'") even with a valid
            // Authorization header supplied via extraHeader, because
            // extraHeader doesn't hook into git's own credential subsystem.
            // An inline credential helper does: git calls it directly
            // whenever the server actually challenges for auth.
            return mkdirPrefix + "git -c " + gitTokenCredentialHelper() + " clone " + quotedUrl + " " + CLONE_TARGET_DIR;
        }
        return mkdirPrefix + "git clone " + quotedUrl + " " + CLONE_TARGET_DIR;
    }

    /** Inline credential helper reading the token from the sandbox's own GIT_TOKEN env var -- never written to disk. */
    static String gitTokenCredentialHelper() {
        return "credential.helper='!f() { echo username=x-access-token; echo password=$" + GIT_TOKEN_ENV_VAR + "; }; f'";
    }

    private String parentDirOf(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : path.substring(0, lastSlash);
    }

    private String formatResult(CommandResult result) {
        StringBuilder output = new StringBuilder();
        output.append("exit_code: ").append(result.exitCode()).append('\n');
        if (result.outputTruncated()) {
            output.append("(output truncated to the configured limit)\n");
        }
        if (result.succeeded()) {
            output.append("Repository cloned to ").append(CLONE_TARGET_DIR).append(".\n");
        }
        output.append("stdout:\n").append(result.stdout());
        if (result.stderr() != null && !result.stderr().isBlank()) {
            output.append("\nstderr:\n").append(result.stderr());
        }
        return output.toString();
    }
}
