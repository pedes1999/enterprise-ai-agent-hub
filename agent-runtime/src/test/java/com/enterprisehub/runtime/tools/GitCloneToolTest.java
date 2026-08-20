package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.AuditingTool;
import com.enterprisehub.runtime.audit.ToolExecutionAuditRecord;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.audit.ToolExecutionOutcome;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import com.enterprisehub.runtime.sandbox.SandboxSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GitCloneToolTest {

    private SandboxClient sandboxClient;
    private ToolExecutionListener listener;
    private CredentialResolver credentialResolver;
    private GitCloneTool tool;
    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("tenant-1", "exec-1");

    @BeforeEach
    void setUp() {
        sandboxClient = mock(SandboxClient.class);
        listener = mock(ToolExecutionListener.class);
        credentialResolver = mock(CredentialResolver.class);
        tool = new GitCloneTool(sandboxClient, credentialResolver);
    }

    private void stubSandbox(CommandResult result) {
        when(sandboxClient.create(any())).thenReturn(new SandboxHandle("s1"));
        when(sandboxClient.runCommand(any(), any(), any())).thenReturn(result);
    }

    @Test
    void execute_createsTheParentDirectoryFirst() {
        // Neither /workspace at filesystem root nor its parent creation are
        // writable by the sandbox's default user (discovered via a real
        // failed clone during live verification: exit 1, permission
        // denied on mkdir itself), not assumed. /tmp is writable -- Locking
        // this in so it can't silently regress.
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        stubSandbox(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).startsWith("mkdir -p '/tmp/workspace' && git clone");
    }

    @Test
    void execute_noCredentialConfigured_clonesWithoutAuthHeader() {
        when(credentialResolver.resolve("tenant-1", "GIT")).thenReturn(Map.of());
        stubSandbox(new CommandResult(0, "Cloning into 'repo'...", "", false, Duration.ofSeconds(1)));

        String result = tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git"));

        assertThat(result).contains("exit_code: 0").contains("Repository cloned to /tmp/workspace/repo");
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).doesNotContain("x-access-token").contains("git clone");
    }

    @Test
    void execute_withGitCredential_embedsTokenRefInUrl_neverEmbedsRawTokenInCommand_thenScrubsOrigin() {
        when(credentialResolver.resolve("tenant-1", "GIT")).thenReturn(Map.of("GIT_TOKEN", "ghp_supersecrettoken"));
        stubSandbox(new CommandResult(0, "Cloning into 'repo'...", "", false, Duration.ofSeconds(1)));

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        String command = commandCaptor.getValue();
        // URL-embedded Basic auth, not credential.helper -- see
        // AuthenticatedGitUrl's javadoc for why credential.helper was
        // dropped (found via live testing to silently fail to deliver the
        // password through to git in this sandbox).
        assertThat(command).contains("x-access-token:$GIT_TOKEN@").contains("git clone");
        // The clone's own origin remote must be rewritten back to the
        // plain (credential-free) URL in the same command chain, so
        // nothing -- not even a later `cat .git/config` -- can read the
        // token back out.
        assertThat(command).contains("remote set-url origin 'https://github.com/org/repo.git'");
        // The raw secret value must never appear in the command text itself --
        // only the sandbox's own env var reference ($GIT_TOKEN) does. The
        // actual value flows through SandboxSpec.credentials -> the sidecar's
        // separate envVars channel, never through the command string.
        assertThat(command).doesNotContain("ghp_supersecrettoken");
    }

    @Test
    void execute_credentialsPassedToSandboxSpec() {
        when(credentialResolver.resolve("tenant-1", "GIT")).thenReturn(Map.of("GIT_TOKEN", "ghp_secret"));
        stubSandbox(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git"));

        ArgumentCaptor<SandboxSpec> specCaptor = ArgumentCaptor.forClass(SandboxSpec.class);
        verify(sandboxClient).create(specCaptor.capture());
        assertThat(specCaptor.getValue().credentials()).containsEntry("GIT_TOKEN", "ghp_secret");
    }

    @Test
    void execute_nonHttpsUrl_rejectedBeforeTouchingSandbox() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("repositoryUrl", "git@github.com:org/repo.git")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient, credentialResolver);
    }

    @Test
    void execute_urlStartingWithDash_rejected_argumentInjectionDefense() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("repositoryUrl", "--upload-pack=evil")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_blankUrl_rejected() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of("repositoryUrl", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_missingUrlArgument_rejected() {
        assertThatThrownBy(() -> tool.execute(CONTEXT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_urlWithShellMetacharacters_isShellQuoted_notInjected() {
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        stubSandbox(new CommandResult(128, "", "fatal: repository not found", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo'; rm -rf / #.git"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        // The whole URL, including the embedded quote, must be wrapped as a
        // single shell-safe argument -- the escaped quote sequence proves it
        // was quoted, not concatenated raw into the command.
        assertThat(commandCaptor.getValue()).contains("'\\''");
    }

    @Test
    void execute_cloneFails_reportedInResult_notThrown() {
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        stubSandbox(new CommandResult(128, "", "fatal: repository not found", false, Duration.ZERO));

        String result = tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/does-not-exist.git"));

        assertThat(result).contains("exit_code: 128").contains("fatal: repository not found");
        assertThat(result).doesNotContain("Repository cloned"); // only printed on success
    }

    /** Auditing lives in AuditingTool now, not in the tool -- so it is exercised through the wrapper ToolCatalog applies. */
    @Test
    void execute_success_audited() {
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        stubSandbox(new CommandResult(0, "ok", "", false, Duration.ofMillis(500)));

        new AuditingTool(tool, listener).execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git"));

        ArgumentCaptor<ToolExecutionAuditRecord> captor = ArgumentCaptor.forClass(ToolExecutionAuditRecord.class);
        verify(listener).onToolExecuted(captor.capture());
        assertThat(captor.getValue().toolName()).isEqualTo("git_clone");
        assertThat(captor.getValue().outcome()).isEqualTo(ToolExecutionOutcome.SUCCESS);
    }

    @Test
    void execute_sandboxDestroyedAfterClone() {
        SandboxHandle handle = new SandboxHandle("s1");
        when(sandboxClient.create(any())).thenReturn(handle);
        when(sandboxClient.runCommand(any(), any(), any())).thenReturn(new CommandResult(0, "", "", false, Duration.ZERO));
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git"));

        verify(sandboxClient).destroy(handle);
    }

    @Test
    void nameAndDescription_areNonEmpty() {
        assertThat(tool.name()).isEqualTo("git_clone");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.parameterDescriptions()).containsKey("repositoryUrl");
    }

    @Test
    void branch_isDeclaredOptional() {
        assertThat(tool.parameterDescriptions()).containsKey("branch");
        assertThat(tool.optionalParameterNames()).containsExactly("branch");
    }

    @Test
    void execute_branchOmitted_commandUnchanged_noRegressionOnExistingBehavior() {
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        stubSandbox(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue())
                .isEqualTo("mkdir -p '/tmp/workspace' && git clone 'https://github.com/org/repo.git' /tmp/workspace/repo");
    }

    @Test
    void execute_branchGiven_addsBranchFlagBeforeTheUrl() {
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        stubSandbox(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git", "branch", "demo/broken-test"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue())
                .isEqualTo("mkdir -p '/tmp/workspace' && git clone -b 'demo/broken-test' 'https://github.com/org/repo.git' /tmp/workspace/repo");
    }

    @Test
    void execute_branchGiven_withCredential_branchFlagStillBeforeAuthenticatedUrl() {
        when(credentialResolver.resolve("tenant-1", "GIT")).thenReturn(Map.of("GIT_TOKEN", "ghp_secret"));
        stubSandbox(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git", "branch", "main"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).contains("git clone -b 'main' \"https://x-access-token:$GIT_TOKEN@");
    }

    @Test
    void execute_blankBranch_treatedSameAsOmitted() {
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        stubSandbox(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git", "branch", "  "));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).doesNotContain("-b ");
    }

    @Test
    void execute_branchStartingWithDash_rejected_argumentInjectionDefense() {
        assertThatThrownBy(() -> tool.execute(CONTEXT,
                Map.of("repositoryUrl", "https://github.com/org/repo.git", "branch", "--upload-pack=evil")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void execute_branchWithShellMetacharacters_isShellQuoted_notInjected() {
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        stubSandbox(new CommandResult(0, "", "", false, Duration.ZERO));

        tool.execute(CONTEXT, Map.of("repositoryUrl", "https://github.com/org/repo.git", "branch", "a'; rm -rf / #"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxClient).runCommand(any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue()).contains("'\\''");
    }
}
