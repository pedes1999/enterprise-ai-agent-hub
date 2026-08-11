package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionAuditRecord;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.audit.ToolExecutionOutcome;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OpenPullRequestToolTest {

    private SandboxClient sandboxClient;
    private ToolExecutionListener listener;
    private CredentialResolver credentialResolver;
    private OpenPullRequestTool tool;
    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("tenant-1", "exec-1");

    private static final Map<String, String> BASE_ARGS = Map.of(
            "repositoryUrl", "https://github.com/octocat/Hello-World.git",
            "branchName", "fix/bug",
            "title", "Fix the bug",
            "body", "Fixes a real bug.",
            "testCommand", "mvn test");

    @BeforeEach
    void setUp() {
        sandboxClient = mock(SandboxClient.class);
        listener = mock(ToolExecutionListener.class);
        credentialResolver = mock(CredentialResolver.class);
        tool = new OpenPullRequestTool(sandboxClient, listener, credentialResolver);
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of("GITHUB_TOKEN", "ghp_secret"));
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
    }

    private CommandResult ok(String stdout) {
        return new CommandResult(0, stdout, "", false, Duration.ZERO);
    }

    private CommandResult failed(int exitCode, String stderr) {
        return new CommandResult(exitCode, "", stderr, false, Duration.ZERO);
    }

    @Test
    void execute_testCommandFails_neverRunsGitOrCurl_reportsFailure() {
        when(sandboxClient.runCommand(any(), any(), any())).thenReturn(failed(1, "test failure output"));

        String result = tool.execute(CONTEXT, BASE_ARGS);

        assertThat(result).contains("Tests FAILED").contains("NOT opened").contains("test failure output");
        verify(sandboxClient, times(1)).runCommand(any(), any(), any());
    }

    @Test
    void execute_testCommandFails_commandRunsInsideWorkspace() {
        when(sandboxClient.runCommand(any(), any(), any())).thenReturn(failed(1, ""));

        tool.execute(CONTEXT, BASE_ARGS);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).contains("cd /tmp/workspace/repo").contains("mvn test");
    }

    @Test
    void execute_testsPass_pushFails_neverOpensPr_reportsFailure() {
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(ok("tests passed"))
                .thenReturn(failed(1, "failed to push: rejected"));

        String result = tool.execute(CONTEXT, BASE_ARGS);

        assertThat(result).contains("committing/pushing the branch failed").contains("failed to push: rejected");
        verify(sandboxClient, times(2)).runCommand(any(), any(), any());
    }

    @Test
    void execute_allStepsSucceed_returnsPullRequestUrl() {
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(ok("tests passed"))
                .thenReturn(ok("pushed"))
                .thenReturn(ok("{\"default_branch\":\"master\"}"))
                .thenReturn(ok("{\"html_url\":\"https://github.com/octocat/Hello-World/pull/42\",\"number\":42}"));

        String result = tool.execute(CONTEXT, BASE_ARGS);

        assertThat(result).contains("Pull request opened successfully")
                .contains("https://github.com/octocat/Hello-World/pull/42")
                .contains("#42");
        verify(sandboxClient, times(4)).runCommand(any(), any(), any());
    }

    @Test
    void execute_githubApiReturnsError_reportsMessageNotRawJson() {
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(ok("tests passed"))
                .thenReturn(ok("pushed"))
                .thenReturn(ok("{\"default_branch\":\"master\"}"))
                .thenReturn(ok("{\"message\":\"Validation Failed\",\"errors\":[]}"));

        String result = tool.execute(CONTEXT, BASE_ARGS);

        assertThat(result).contains("opening the pull request failed").contains("Validation Failed");
    }

    @Test
    void execute_pushCommand_usesGithubTokenEnvVar_neverEmbedsRawToken() {
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(ok("tests passed"))
                .thenReturn(ok("pushed"))
                .thenReturn(ok("{\"default_branch\":\"master\"}"))
                .thenReturn(ok("{}"));

        tool.execute(CONTEXT, BASE_ARGS);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient, times(4)).runCommand(any(), commandCaptor.capture(), any());
        String pushCommand = commandCaptor.getAllValues().get(1);
        assertThat(pushCommand).contains("x-access-token:$GITHUB_TOKEN@").contains("git push").doesNotContain("ghp_secret");
    }

    @Test
    void execute_titleWithShellMetacharacters_isShellQuoted() {
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(ok("tests passed"))
                .thenReturn(ok("pushed"))
                .thenReturn(ok("{\"default_branch\":\"master\"}"))
                .thenReturn(ok("{}"));

        Map<String, String> args = Map.of(
                "repositoryUrl", "https://github.com/octocat/Hello-World.git",
                "branchName", "fix/bug",
                "title", "Fix it'; rm -rf / #done",
                "testCommand", "mvn test");
        tool.execute(CONTEXT, args);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient, times(4)).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getAllValues().get(1)).contains("'\\''");
    }

    @Test
    void execute_missingTestCommand_rejectedBeforeTouchingSandbox() {
        Map<String, String> args = Map.of(
                "repositoryUrl", "https://github.com/octocat/Hello-World.git",
                "branchName", "fix/bug",
                "title", "Fix it");
        assertThatThrownBy(() -> tool.execute(CONTEXT, args)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_nonGitHubUrl_rejected() {
        Map<String, String> args = Map.of(
                "repositoryUrl", "https://gitlab.com/org/repo.git",
                "branchName", "fix/bug",
                "title", "Fix it",
                "testCommand", "mvn test");
        assertThatThrownBy(() -> tool.execute(CONTEXT, args)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_malformedRepositoryUrl_rejected() {
        Map<String, String> args = Map.of(
                "repositoryUrl", "https://github.com/just-one-segment",
                "branchName", "fix/bug",
                "title", "Fix it",
                "testCommand", "mvn test");
        assertThatThrownBy(() -> tool.execute(CONTEXT, args)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_noGithubCredentialConfigured_throwsBeforeTouchingSandbox() {
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());

        assertThatThrownBy(() -> tool.execute(CONTEXT, BASE_ARGS)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_missingBaseBranch_resolvesRepositoryActualDefaultBranch() {
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(ok("tests passed"))
                .thenReturn(ok("pushed"))
                .thenReturn(ok("{\"default_branch\":\"master\"}"))
                .thenReturn(ok("{}"));

        tool.execute(CONTEXT, BASE_ARGS);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient, times(4)).runCommand(any(), commandCaptor.capture(), any());
        // Step 3: looks up the repo's real default branch via the GitHub API -- not hardcoded to 'main'.
        assertThat(commandCaptor.getAllValues().get(2)).contains("api.github.com/repos/octocat/Hello-World")
                .doesNotContain("/pulls");
        // Step 4: the PR payload uses whatever that lookup returned.
        assertThat(commandCaptor.getAllValues().get(3)).contains("\"base\":\"master\"");
    }

    @Test
    void execute_defaultBranchLookupFails_fallsBackToMain() {
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(ok("tests passed"))
                .thenReturn(ok("pushed"))
                .thenReturn(ok("not valid json"))
                .thenReturn(ok("{}"));

        tool.execute(CONTEXT, BASE_ARGS);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient, times(4)).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getAllValues().get(3)).contains("\"base\":\"main\"");
    }

    @Test
    void execute_explicitBaseBranch_skipsDefaultBranchLookup() {
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(ok("tests passed"))
                .thenReturn(ok("pushed"))
                .thenReturn(ok("{}"));

        Map<String, String> args = new java.util.HashMap<>(BASE_ARGS);
        args.put("baseBranch", "develop");
        tool.execute(CONTEXT, args);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient, times(3)).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getAllValues().get(2)).contains("\"base\":\"develop\"");
    }

    @Test
    void execute_sandboxDestroyedAfterPipeline() {
        SandboxHandle handle = new SandboxHandle("s1");
        when(sandboxClient.create(any())).thenReturn(handle);
        when(sandboxClient.runCommand(any(), any(), any())).thenReturn(failed(1, "fail"));

        tool.execute(CONTEXT, BASE_ARGS);

        verify(sandboxClient).destroy(handle);
    }

    @Test
    void execute_success_audited() {
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(ok("tests passed"))
                .thenReturn(ok("pushed"))
                .thenReturn(ok("{\"default_branch\":\"master\"}"))
                .thenReturn(ok("{\"html_url\":\"https://github.com/x/y/pull/1\",\"number\":1}"));

        tool.execute(CONTEXT, BASE_ARGS);

        ArgumentCaptor<ToolExecutionAuditRecord> captor = ArgumentCaptor.forClass(ToolExecutionAuditRecord.class);
        verify(listener).onToolExecuted(captor.capture());
        assertThat(captor.getValue().toolName()).isEqualTo("open_pull_request");
        assertThat(captor.getValue().outcome()).isEqualTo(ToolExecutionOutcome.SUCCESS);
    }

    @Test
    void nameAndDescription_areNonEmpty() {
        assertThat(tool.name()).isEqualTo("open_pull_request");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.parameterDescriptions()).containsKeys(
                "repositoryUrl", "branchName", "title", "body", "testCommand", "baseBranch");
    }
}
