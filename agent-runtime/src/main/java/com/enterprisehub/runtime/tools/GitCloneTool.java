package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxSpec;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

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
                + "modifying files in a repository. Only HTTPS repository URLs are supported. Pass branch to "
                + "check out something other than the repository's default branch.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of(
                "repositoryUrl", "HTTPS URL of the repository to clone, e.g. https://github.com/org/repo.git",
                "branch", "Optional. Branch to check out after cloning, e.g. main or feature/my-branch. "
                        + "Omit this argument entirely to get the repository's default branch.");
    }

    @Override
    public Set<String> optionalParameterNames() {
        return Set.of("branch");
    }

    @Override
    protected String doExecute(ToolExecutionContext context, Map<String, String> arguments) {
        String repositoryUrl = arguments.get("repositoryUrl");
        validateRepositoryUrl(repositoryUrl);
        String branch = arguments.get("branch");
        validateBranch(branch);

        Map<String, String> credentials = credentialResolver.resolve(context.tenantId(), GIT_CREDENTIAL_KIND);
        String command = buildCloneCommand(repositoryUrl, credentials.containsKey(GIT_TOKEN_ENV_VAR), branch);

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

    private void validateBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            // Optional -- absent/blank means "clone the default branch", the
            // pre-existing behavior. Nothing more to validate.
            return;
        }
        if (branch.startsWith("-")) {
            // Same argument-injection defense as validateRepositoryUrl:
            // branch is passed as `-b <value>` below, quoted, so it can't
            // break out of that argument -- but a leading '-' is still
            // rejected on principle rather than trusting git's positional
            // binding of -b's value to save it in every version/edge case.
            throw new IllegalArgumentException("branch must not start with '-'");
        }
    }

    private String buildCloneCommand(String repositoryUrl, boolean hasCredential, String branch) {
        // /workspace does not exist by default in a fresh E2B sandbox --
        // discovered by an actual failed clone ("could not create leading
        // directories... Permission denied") during live verification, not
        // assumed. mkdir -p first so a real clone failure (bad URL, auth,
        // network) is what surfaces, not a filesystem setup issue.
        String mkdirPrefix = "mkdir -p " + ShellQuoting.quote(parentDirOf(CLONE_TARGET_DIR)) + " && ";
        String quotedUrl = ShellQuoting.quote(repositoryUrl);
        // -b/--branch works for both branches and tags with a single
        // `git clone` call -- no separate `git checkout` step needed, and
        // it fails the whole clone up front with a clear error if the ref
        // doesn't exist, rather than succeeding on the default branch and
        // silently leaving the agent on the wrong ref.
        String branchFlag = (branch != null && !branch.isBlank()) ? "-b " + ShellQuoting.quote(branch) + " " : "";
        if (hasCredential) {
            // Token embedded in the clone URL as Basic-auth userinfo, then
            // the clone's own origin remote is immediately rewritten back
            // to the plain URL in the SAME command chain -- see
            // AuthenticatedGitUrl's javadoc for why this replaced an
            // earlier `-c credential.helper=...` attempt (found via live
            // testing to silently fail to deliver the password through to
            // git in this sandbox). Nothing here persists the token on
            // disk: if `git clone` fails the `&&` chain short-circuits
            // before `remote set-url` ever runs, and if it succeeds the
            // credential-bearing URL is gone from .git/config before this
            // command even returns.
            String authenticatedUrl = AuthenticatedGitUrl.build(repositoryUrl, GIT_TOKEN_ENV_VAR);
            return mkdirPrefix + "git clone " + branchFlag + authenticatedUrl + " " + CLONE_TARGET_DIR
                    + " && git -C " + CLONE_TARGET_DIR + " remote set-url origin " + quotedUrl;
        }
        return mkdirPrefix + "git clone " + branchFlag + quotedUrl + " " + CLONE_TARGET_DIR;
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
