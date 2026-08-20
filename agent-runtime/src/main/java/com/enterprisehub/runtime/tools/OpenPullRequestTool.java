package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import com.enterprisehub.runtime.sandbox.SandboxSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The final step of a real Ticket-to-PR loop: commit whatever's in the
 * shared workspace, push it, and open a pull request -- GitHub specifically
 * (the REST API call, the credential kind, and the owner/repo parsing are
 * all GitHub-shaped; a second host would need its own tool, not a
 * parameter on this one).
 *
 * testCommand is NOT optional. It is re-run, for real, inside the same
 * sandbox, immediately before anything else happens -- if it fails (any
 * non-zero exit code), NO branch is created, NOTHING is committed or
 * pushed, and NO pull request is opened. This does not trust the model's
 * own claim that it verified its work; it trusts a real exit code, the
 * same "enforce it at the point that matters, not the caller" posture RLS
 * uses for tenant isolation and RunShellCommandTool uses for command
 * results.
 */
public class OpenPullRequestTool extends AbstractSandboxedTool {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration SANDBOX_MAX_LIFETIME = Duration.ofMinutes(10);
    private static final long MAX_OUTPUT_BYTES = 64 * 1024;
    private static final String GITHUB_CREDENTIAL_KIND = "GITHUB";
    private static final String GITHUB_TOKEN_ENV_VAR = "GITHUB_TOKEN";
    private static final String COMMIT_AUTHOR_EMAIL = "agent@enterprise-ai-agent-hub.local";
    private static final String COMMIT_AUTHOR_NAME = "Enterprise AI Agent Hub";

    private final CredentialResolver credentialResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenPullRequestTool(SandboxClient sandboxClient, CredentialResolver credentialResolver) {
        super(sandboxClient);
        this.credentialResolver = credentialResolver;
    }

    @Override
    public String name() {
        return "open_pull_request";
    }

    @Override
    public String description() {
        return "Commits everything currently in the workspace, pushes it to a new branch, and opens a pull "
                + "request on GitHub. ALWAYS runs testCommand first, inside the sandbox -- if it fails, nothing is "
                + "committed, pushed, or opened, regardless of what this tool call claims should happen. Only use "
                + "this once you believe the change is complete and ready for review.";
    }

    /**
     * "Pull request opened successfully: ..." (see formatPullRequestResult
     * below) is the one result shape from this tool that means the task's
     * actual goal was achieved -- ToolCallingChatEngine uses this to force
     * the loop to stop right there instead of trusting the model to notice
     * and stop calling tools on its own.
     */
    @Override
    public boolean isTerminalSuccess(String result) {
        return result != null && result.startsWith("Pull request opened successfully:");
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("repositoryUrl", "HTTPS GitHub URL of the repository, e.g. https://github.com/owner/repo.git (must match the repository already cloned into the workspace).");
        params.put("branchName", "Name of the new branch to create, commit to, and push, e.g. 'fix/null-pointer-in-parser'.");
        params.put("title", "Pull request title.");
        params.put("body", "Pull request description. May be blank.");
        params.put("testCommand", "Shell command that must exit 0 for this pull request to be opened at all, e.g. 'mvn test' or 'npm test'. Required -- if the repository genuinely has no tests, pass a build or lint command instead.");
        params.put("baseBranch", "Branch to open the pull request against. Defaults to the repository's actual default branch (resolved via the GitHub API) if omitted -- do not assume 'main'.");
        return params;
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        String repositoryUrl = requireArgument(arguments, "repositoryUrl");
        String[] ownerAndRepo = parseGitHubOwnerAndRepo(repositoryUrl);
        String branchName = requireArgument(arguments, "branchName");
        validateBranchName(branchName);
        String title = requireArgument(arguments, "title");
        String body = arguments.getOrDefault("body", "");
        String testCommand = requireArgument(arguments, "testCommand");
        String requestedBaseBranch = arguments.get("baseBranch");
        if (requestedBaseBranch != null && requestedBaseBranch.isBlank()) {
            requestedBaseBranch = null;
        }
        final String finalRequestedBaseBranch = requestedBaseBranch;

        Map<String, String> credentials = credentialResolver.resolve(context.tenantId(), GITHUB_CREDENTIAL_KIND);
        if (!credentials.containsKey(GITHUB_TOKEN_ENV_VAR)) {
            throw new IllegalStateException(
                    "No GITHUB credential configured for this tenant -- PUT /tool-credentials with credentialKind GITHUB first");
        }

        SandboxSpec spec = new SandboxSpec(
                context.tenantId(), context.executionId(), credentials, SANDBOX_MAX_LIFETIME, MAX_OUTPUT_BYTES);

        return withSandbox(spec, handle ->
                runPipeline(handle, testCommand, branchName, title, body, repositoryUrl, ownerAndRepo, finalRequestedBaseBranch));
    }

    private String runPipeline(SandboxHandle handle, String testCommand, String branchName, String title,
                                String body, String repositoryUrl, String[] ownerAndRepo, String requestedBaseBranch) {
        CommandResult testResult = sandboxClient.runCommand(handle, buildTestCommand(testCommand), COMMAND_TIMEOUT);
        if (!testResult.succeeded()) {
            return "Tests FAILED -- pull request was NOT opened. Nothing was committed or pushed.\n"
                    + formatCommandResult(testResult);
        }

        CommandResult pushResult = sandboxClient.runCommand(
                handle, buildPushCommand(branchName, title, repositoryUrl), COMMAND_TIMEOUT);
        if (!pushResult.succeeded()) {
            return "Tests passed, but committing/pushing the branch failed -- pull request was NOT opened.\n"
                    + formatCommandResult(pushResult);
        }

        String baseBranch = requestedBaseBranch != null
                ? requestedBaseBranch
                : resolveDefaultBranch(handle, ownerAndRepo);

        CommandResult prResult = sandboxClient.runCommand(
                handle, buildOpenPrCommand(ownerAndRepo, branchName, title, body, baseBranch), COMMAND_TIMEOUT);
        return formatPullRequestResult(prResult);
    }

    /**
     * Only called when the caller didn't pass baseBranch -- asks GitHub
     * itself rather than assuming 'main' (plenty of repos, including this
     * one, still default to 'master'). Falls back to 'main' if the lookup
     * itself fails for any reason (network hiccup, unparseable response);
     * the PR-open step's own error message will then explain the real
     * problem rather than this resolution step failing silently.
     */
    private String resolveDefaultBranch(SandboxHandle handle, String[] ownerAndRepo) {
        CommandResult result = sandboxClient.runCommand(handle, buildGetRepoCommand(ownerAndRepo), COMMAND_TIMEOUT);
        try {
            JsonNode json = objectMapper.readTree(result.stdout());
            JsonNode defaultBranch = json.get("default_branch");
            if (defaultBranch != null && defaultBranch.isTextual()) {
                return defaultBranch.asText();
            }
        } catch (Exception e) {
            // Falls through to the "main" fallback below.
        }
        return "main";
    }

    private String buildGetRepoCommand(String[] ownerAndRepo) {
        String url = "https://api.github.com/repos/" + ownerAndRepo[0] + "/" + ownerAndRepo[1];
        return "curl -s -H \"Authorization: Bearer $" + GITHUB_TOKEN_ENV_VAR + "\" "
                + "-H \"Accept: application/vnd.github+json\" " + url;
    }

    private String buildTestCommand(String testCommand) {
        return "cd " + Workspace.ROOT + " && (" + testCommand + ")";
    }

    private String buildPushCommand(String branchName, String title, String repositoryUrl) {
        // Pushes straight to an authenticated URL rather than `origin` --
        // see AuthenticatedGitUrl's javadoc for why (replaced an earlier
        // `-c credential.helper=...` that looked correct but silently
        // failed to deliver the password through to git in this sandbox).
        // A URL-target push never touches .git/config at all, so there's
        // nothing to scrub afterward.
        String authenticatedUrl = AuthenticatedGitUrl.build(repositoryUrl, GITHUB_TOKEN_ENV_VAR);
        return "cd " + Workspace.ROOT + " && "
                + "git checkout -b " + ShellQuoting.quote(branchName) + " && "
                + "git add -A && "
                + "git -c user.email=" + ShellQuoting.quote(COMMIT_AUTHOR_EMAIL)
                + " -c user.name=" + ShellQuoting.quote(COMMIT_AUTHOR_NAME)
                + " commit -m " + ShellQuoting.quote(title) + " && "
                + "git push " + authenticatedUrl + " " + ShellQuoting.quote(branchName);
    }

    private String buildOpenPrCommand(String[] ownerAndRepo, String branchName, String title, String body, String baseBranch) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "title", title, "head", branchName, "base", baseBranch, "body", body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build the pull request payload", e);
        }
        String url = "https://api.github.com/repos/" + ownerAndRepo[0] + "/" + ownerAndRepo[1] + "/pulls";
        return "curl -s -X POST -H \"Authorization: Bearer $" + GITHUB_TOKEN_ENV_VAR + "\" "
                + "-H \"Accept: application/vnd.github+json\" " + url + " -d " + ShellQuoting.quote(payload);
    }

    private String formatPullRequestResult(CommandResult curlResult) {
        JsonNode json;
        try {
            json = objectMapper.readTree(curlResult.stdout());
        } catch (Exception e) {
            return "Branch pushed successfully, but the GitHub API response couldn't be parsed:\n" + curlResult.stdout();
        }
        JsonNode htmlUrl = json.get("html_url");
        if (htmlUrl != null && htmlUrl.isTextual()) {
            JsonNode number = json.get("number");
            return "Pull request opened successfully: " + htmlUrl.asText()
                    + (number != null ? " (#" + number.asInt() + ")" : "");
        }
        JsonNode message = json.get("message");
        return "Branch pushed successfully, but opening the pull request failed: "
                + (message != null ? message.asText() : curlResult.stdout());
    }

    private String requireArgument(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " argument is required");
        }
        return value;
    }

    private void validateBranchName(String branchName) {
        if (branchName.startsWith("-")) {
            throw new IllegalArgumentException("branchName must not start with '-'");
        }
    }

    /** GitHub-only by design -- see class javadoc. */
    private String[] parseGitHubOwnerAndRepo(String repositoryUrl) {
        if (!repositoryUrl.startsWith("https://github.com/")) {
            throw new IllegalArgumentException("repositoryUrl must be an https://github.com/... URL");
        }
        String path = repositoryUrl.substring("https://github.com/".length());
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] parts = path.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("repositoryUrl must look like https://github.com/owner/repo.git");
        }
        return parts;
    }

    private String formatCommandResult(CommandResult result) {
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
