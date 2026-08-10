package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.SharedExecutionContext;
import com.enterprisehub.core.SharedExecutionContextFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.credential.VendorCredentialService;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentPromptRunnerTest {

    private VendorCredentialRepository vendorCredentialRepository;
    private VendorCredentialService vendorCredentialService;
    private SharedExecutionContextFactory sharedExecutionContextFactory;
    private ChatLanguageModel chatLanguageModel;
    private AgentPromptRunner runner;
    private CredentialResolver credentialResolver;
    private SandboxClient sandboxClient;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialRepository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        sharedExecutionContextFactory = mock(SharedExecutionContextFactory.class);
        chatLanguageModel = mock(ChatLanguageModel.class);
        LlmProperties properties = new LlmProperties("claude-3-5-sonnet-20240620");
        sandboxClient = mock(SandboxClient.class);
        ToolExecutionListener toolExecutionListener = mock(ToolExecutionListener.class);
        credentialResolver = mock(CredentialResolver.class);
        // buildSessionSpec() resolves GIT credentials up front for every
        // run() call now (see AgentPromptRunner's javadoc on why) -- Map.of()
        // by default, same as JpaCredentialResolver's real "nothing configured" behavior.
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        runner = new AgentPromptRunner(vendorCredentialRepository, vendorCredentialService,
                sharedExecutionContextFactory, properties, sandboxClient, toolExecutionListener, credentialResolver);
    }

    private VendorCredential activeCredential() {
        VendorCredential credential = new VendorCredential();
        credential.setId(UUID.randomUUID());
        credential.setTenantId(tenantId);
        credential.setProvider("ANTHROPIC");
        credential.setEncryptedToken("ciphertext");
        credential.setEncryptionKeyId("local-v1");
        credential.setActive(true);
        return credential;
    }

    private void stubCredentialResolution() {
        VendorCredential credential = activeCredential();
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
    }

    /**
     * Builds the SharedExecutionContext from whatever REAL tools list
     * AgentPromptRunner.run() actually assembled and passed in (captured
     * via the mock, not a hand-rolled stand-in) -- so tests that exercise
     * git_clone/run_shell_command exercise the real session-wired tool
     * instances, not a fake with only CurrentDateTimeTool.
     */
    private void stubContextFactory(String executionId) {
        when(sharedExecutionContextFactory.create(eq(tenantId.toString()), eq(executionId), eq(LlmProvider.ANTHROPIC),
                eq("sk-ant-real-key"), eq("claude-3-5-sonnet-20240620"), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<com.enterprisehub.core.tool.AgentTool> tools = invocation.getArgument(5);
                    return new SharedExecutionContext(tenantId.toString(), executionId, chatLanguageModel, tools);
                });
    }

    @Test
    void run_modelAnswersDirectly_noToolNeeded() {
        stubCredentialResolution();
        stubContextFactory("exec-1");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("Hi there!")));

        ToolCallingChatEngine.ToolChatResult result = runner.run(tenantId, "exec-1", "Hello");

        assertThat(result.reply()).isEqualTo("Hi there!");
        assertThat(result.toolWasUsed()).isFalse();
    }

    @Test
    void run_modelCallsTheDateTimeTool_toolWasUsedIsTrue() {
        stubCredentialResolution();
        stubContextFactory("exec-2");

        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("get_current_date_time")
                .arguments("{\"timezone\":\"UTC\"}")
                .build();

        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(toolRequest))))
                .thenReturn(Response.from(AiMessage.from("It is currently 2026-01-01T00:00:00Z")));

        ToolCallingChatEngine.ToolChatResult result = runner.run(tenantId, "exec-2", "What time is it in UTC?");

        assertThat(result.toolWasUsed()).isTrue();
        assertThat(result.reply()).contains("2026-01-01");
        verify(chatLanguageModel, times(2)).generate(anyList(), anyList());
    }

    @Test
    void run_multipleSandboxedToolCallsInOneRun_shareOneSandbox_destroyedOnce() {
        stubCredentialResolution();
        stubContextFactory("exec-shared");

        ToolExecutionRequest cloneRequest = ToolExecutionRequest.builder()
                .id("call-1").name("git_clone").arguments("{\"repositoryUrl\":\"https://github.com/org/repo.git\"}").build();
        ToolExecutionRequest shellRequest = ToolExecutionRequest.builder()
                .id("call-2").name("run_shell_command").arguments("{\"command\":\"ls\"}").build();

        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(cloneRequest))))
                .thenReturn(Response.from(AiMessage.from(List.of(shellRequest))))
                .thenReturn(Response.from(AiMessage.from("Cloned and listed files")));

        com.enterprisehub.runtime.sandbox.SandboxHandle handle = new com.enterprisehub.runtime.sandbox.SandboxHandle("shared-1");
        when(sandboxClient.create(any())).thenReturn(handle);
        when(sandboxClient.runCommand(any(), any(), any()))
                .thenReturn(new com.enterprisehub.runtime.sandbox.CommandResult(0, "ok", "", false, java.time.Duration.ZERO));

        ToolCallingChatEngine.ToolChatResult result = runner.run(tenantId, "exec-shared", "clone then list files");

        assertThat(result.toolWasUsed()).isTrue();
        // Two sandboxed tool calls happened, but only ONE real sandbox was
        // ever provisioned and destroyed -- this is SandboxSession's whole
        // point (see AgentPromptRunner's javadoc).
        verify(sandboxClient, times(1)).create(any());
        verify(sandboxClient, times(1)).destroy(handle);
    }

    @Test
    void run_sandboxNeverTouched_endSessionStillSafe_noDestroyCall() {
        stubCredentialResolution();
        stubContextFactory("exec-notools");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("no tool needed")));

        runner.run(tenantId, "exec-notools", "just answer directly");

        verifyNoInteractions(sandboxClient);
    }

    @Test
    void run_noCredentialConfigured_throwsBadRequest() {
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runner.run(tenantId, "exec-3", "Hello"))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(sharedExecutionContextFactory);
    }

    @Test
    void run_providerCallThrows_propagatesRuntimeException() {
        stubCredentialResolution();
        stubContextFactory("exec-4");
        when(chatLanguageModel.generate(anyList(), anyList())).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> runner.run(tenantId, "exec-4", "Hello"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void modelName_returnsConfiguredModel() {
        assertThat(runner.modelName()).isEqualTo("claude-3-5-sonnet-20240620");
    }
}
