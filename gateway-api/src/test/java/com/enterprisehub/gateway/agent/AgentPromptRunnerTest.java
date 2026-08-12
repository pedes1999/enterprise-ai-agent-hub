package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.SharedExecutionContext;
import com.enterprisehub.core.SharedExecutionContextFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.agent.catalog.CurrentDateTimeToolFactory;
import com.enterprisehub.gateway.agent.catalog.GitCloneToolFactory;
import com.enterprisehub.gateway.agent.catalog.OpenPullRequestToolFactory;
import com.enterprisehub.gateway.agent.catalog.ReadFileToolFactory;
import com.enterprisehub.gateway.agent.catalog.RunShellCommandToolFactory;
import com.enterprisehub.gateway.agent.catalog.ToolCatalog;
import com.enterprisehub.gateway.agent.catalog.WriteFileToolFactory;
import com.enterprisehub.gateway.agent.input.InputSourceResolverRegistry;
import com.enterprisehub.gateway.agent.input.ManualTextInputResolver;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.credential.VendorCredentialService;
import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.gateway.tenant.TenantLlmProviderResolver;
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

    private static final String AGENT_SLUG = "test-agent";

    private VendorCredentialRepository vendorCredentialRepository;
    private VendorCredentialService vendorCredentialService;
    private SharedExecutionContextFactory sharedExecutionContextFactory;
    private TenantLlmProviderResolver tenantLlmProviderResolver;
    private ChatLanguageModel chatLanguageModel;
    private AgentPromptRunner runner;
    private CredentialResolver credentialResolver;
    private SandboxClient sandboxClient;
    private AgentDefinitionRepository agentDefinitionRepository;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialRepository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        sharedExecutionContextFactory = mock(SharedExecutionContextFactory.class);
        chatLanguageModel = mock(ChatLanguageModel.class);
        LlmProperties properties = new LlmProperties("ANTHROPIC", "claude-3-5-sonnet-20240620", null, null);
        tenantLlmProviderResolver = mock(TenantLlmProviderResolver.class);
        when(tenantLlmProviderResolver.resolve(tenantId)).thenReturn(LlmProvider.ANTHROPIC);
        sandboxClient = mock(SandboxClient.class);
        ToolExecutionListener toolExecutionListener = mock(ToolExecutionListener.class);
        credentialResolver = mock(CredentialResolver.class);
        agentDefinitionRepository = mock(AgentDefinitionRepository.class);
        // buildSessionSpec() resolves GIT credentials up front for every
        // run() call now (see AgentPromptRunner's javadoc on why) -- Map.of()
        // by default, same as JpaCredentialResolver's real "nothing configured" behavior.
        when(credentialResolver.resolve(any(), any())).thenReturn(Map.of());
        // Default stub: a definition with every tool a test might need --
        // individual tests override this only when testing a different agent.
        when(agentDefinitionRepository.findBySlugAndActiveTrue(any())).thenReturn(Optional.of(testDefinition(
                "get_current_date_time", "git_clone", "run_shell_command", "read_file", "write_file", "open_pull_request")));
        ToolCatalog toolCatalog = new ToolCatalog(List.of(
                new CurrentDateTimeToolFactory(), new RunShellCommandToolFactory(),
                new GitCloneToolFactory(), new ReadFileToolFactory(), new WriteFileToolFactory(),
                new OpenPullRequestToolFactory()));
        InputSourceResolverRegistry inputSourceResolverRegistry = new InputSourceResolverRegistry(List.of(new ManualTextInputResolver()));
        runner = new AgentPromptRunner(vendorCredentialRepository, vendorCredentialService,
                sharedExecutionContextFactory, properties, tenantLlmProviderResolver, sandboxClient, toolExecutionListener, credentialResolver,
                agentDefinitionRepository, toolCatalog, inputSourceResolverRegistry);
    }

    private AgentDefinition testDefinition(String... toolNames) {
        AgentDefinition definition = new AgentDefinition();
        definition.setSlug(AGENT_SLUG);
        definition.setName("Test Agent");
        definition.setDescription("used only in tests");
        definition.setSystemPrompt("You are a test agent.");
        definition.setToolNames(List.of(toolNames));
        return definition;
    }

    private AgentDefinition testDefinitionWithInputSource(String inputSourceType, String... toolNames) {
        AgentDefinition definition = testDefinition(toolNames);
        definition.setInputSourceType(inputSourceType);
        return definition;
    }

    /** Captures the actual UserMessage text sent to the model -- index 1 because every test agent here has a non-null systemPrompt (index 0). */
    private String capturedUserMessageText() {
        org.mockito.ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(captor.capture(), anyList());
        return ((dev.langchain4j.data.message.UserMessage) captor.getValue().get(1)).singleText();
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
                eq("sk-ant-real-key"), eq("claude-3-5-sonnet-20240620"), any(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<com.enterprisehub.core.tool.AgentTool> tools = invocation.getArgument(5);
                    String systemPrompt = invocation.getArgument(6);
                    return new SharedExecutionContext(tenantId.toString(), executionId, chatLanguageModel, tools, systemPrompt);
                });
    }

    @Test
    void run_modelAnswersDirectly_noToolNeeded() {
        stubCredentialResolution();
        stubContextFactory("exec-1");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("Hi there!")));

        ToolCallingChatEngine.ToolChatResult result = runner.run(tenantId, "exec-1", AGENT_SLUG, "Hello");

        assertThat(result.reply()).isEqualTo("Hi there!");
        assertThat(result.toolWasUsed()).isFalse();
    }

    @Test
    void run_tenantPrefersLocal_resolvesLocalCredentialAndBaseUrl_notAnthropic() {
        when(tenantLlmProviderResolver.resolve(tenantId)).thenReturn(LlmProvider.LOCAL);
        VendorCredential localCredential = activeCredential();
        localCredential.setProvider("LOCAL");
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "LOCAL")).thenReturn(Optional.of(localCredential));
        when(vendorCredentialService.decryptToken(localCredential)).thenReturn("not-needed");
        when(sharedExecutionContextFactory.create(eq(tenantId.toString()), eq("exec-local"), eq(LlmProvider.LOCAL),
                eq("not-needed"), eq((String) null), any(), any(), eq((String) null)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<com.enterprisehub.core.tool.AgentTool> tools = invocation.getArgument(5);
                    String systemPrompt = invocation.getArgument(6);
                    return new SharedExecutionContext(tenantId.toString(), "exec-local", chatLanguageModel, tools, systemPrompt);
                });
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("Hi from local!")));

        ToolCallingChatEngine.ToolChatResult result = runner.run(tenantId, "exec-local", AGENT_SLUG, "Hello");

        assertThat(result.reply()).isEqualTo("Hi from local!");
        verify(vendorCredentialRepository, never()).findByTenantIdAndProvider(tenantId, "ANTHROPIC");
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

        ToolCallingChatEngine.ToolChatResult result = runner.run(tenantId, "exec-2", AGENT_SLUG, "What time is it in UTC?");

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

        ToolCallingChatEngine.ToolChatResult result = runner.run(tenantId, "exec-shared", AGENT_SLUG, "clone then list files");

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

        runner.run(tenantId, "exec-notools", AGENT_SLUG, "just answer directly");

        verifyNoInteractions(sandboxClient);
    }

    @Test
    void run_noCredentialConfigured_throwsBadRequest() {
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runner.run(tenantId, "exec-3", AGENT_SLUG, "Hello"))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(sharedExecutionContextFactory);
    }

    @Test
    void run_unknownAgentSlug_throwsBadRequest_neverResolvesCredential() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("does-not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runner.run(tenantId, "exec-unknown", "does-not-exist", "Hello"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does-not-exist")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(vendorCredentialRepository);
    }

    @Test
    void run_definitionWithoutGitClone_neverResolvesGitCredential() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue(AGENT_SLUG))
                .thenReturn(Optional.of(testDefinition("get_current_date_time")));
        stubCredentialResolution();
        stubContextFactory("exec-no-git");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("ok")));

        runner.run(tenantId, "exec-no-git", AGENT_SLUG, "Hello");

        verifyNoInteractions(credentialResolver);
    }

    @Test
    void run_definitionWithGitClone_resolvesGitCredentialUpFront() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue(AGENT_SLUG))
                .thenReturn(Optional.of(testDefinition("git_clone")));
        stubCredentialResolution();
        stubContextFactory("exec-with-git");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("ok")));

        runner.run(tenantId, "exec-with-git", AGENT_SLUG, "Hello");

        verify(credentialResolver).resolve(tenantId.toString(), "GIT");
    }

    @Test
    void run_definitionWithoutOpenPullRequest_neverResolvesGithubCredential() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue(AGENT_SLUG))
                .thenReturn(Optional.of(testDefinition("get_current_date_time")));
        stubCredentialResolution();
        stubContextFactory("exec-no-pr");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("ok")));

        runner.run(tenantId, "exec-no-pr", AGENT_SLUG, "Hello");

        verifyNoInteractions(credentialResolver);
    }

    @Test
    void run_definitionWithOpenPullRequest_resolvesGithubCredentialUpFront() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue(AGENT_SLUG))
                .thenReturn(Optional.of(testDefinition("git_clone", "open_pull_request")));
        stubCredentialResolution();
        stubContextFactory("exec-with-pr");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("ok")));

        runner.run(tenantId, "exec-with-pr", AGENT_SLUG, "Hello");

        verify(credentialResolver).resolve(tenantId.toString(), "GIT");
        verify(credentialResolver).resolve(tenantId.toString(), "GITHUB");
    }

    @Test
    void run_passesDefinitionsSystemPromptThrough() {
        stubCredentialResolution();
        stubContextFactory("exec-sysprompt");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("ok")));

        runner.run(tenantId, "exec-sysprompt", AGENT_SLUG, "Hello");

        org.mockito.ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(captor.capture(), anyList());
        assertThat(captor.getValue().get(0)).isInstanceOf(dev.langchain4j.data.message.SystemMessage.class);
        assertThat(((dev.langchain4j.data.message.SystemMessage) captor.getValue().get(0)).text())
                .isEqualTo("You are a test agent.");
    }

    @Test
    void run_noRepositoryUrlNoInputParameters_promptIsByteIdenticalToTodaysBehavior() {
        // Regression guard for general-assistant-style agents: no
        // inputSourceType configured, repositoryUrl/inputParameters both
        // null -- assemblePrompt() must reduce to EXACTLY `prompt`, no
        // stray "Repository: " prefix, no leftover blank lines.
        stubCredentialResolution();
        stubContextFactory("exec-noop-input");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("ok")));

        runner.run(tenantId, "exec-noop-input", AGENT_SLUG, "Hello", null, null);

        assertThat(capturedUserMessageText()).isEqualTo("Hello");
    }

    @Test
    void run_ticketStyleInput_repositoryAndResolvedBlobAndPrompt_allAppearInOrder() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue(AGENT_SLUG))
                .thenReturn(Optional.of(testDefinitionWithInputSource("MANUAL_TEXT", "get_current_date_time")));
        stubCredentialResolution();
        stubContextFactory("exec-ticket-style");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("ok")));

        runner.run(tenantId, "exec-ticket-style", AGENT_SLUG, "Also check the auth module",
                "https://github.com/org/repo.git", Map.of("text", "Ticket: fix the login bug"));

        assertThat(capturedUserMessageText()).isEqualTo(
                "Repository: https://github.com/org/repo.git\n\nTicket: fix the login bug\n\nAlso check the auth module");
    }

    @Test
    void run_ticketStyleInput_blankPrompt_noTrailingEmptySection() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue(AGENT_SLUG))
                .thenReturn(Optional.of(testDefinitionWithInputSource("MANUAL_TEXT", "get_current_date_time")));
        stubCredentialResolution();
        stubContextFactory("exec-ticket-noprompt");
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("ok")));

        runner.run(tenantId, "exec-ticket-noprompt", AGENT_SLUG, "",
                "https://github.com/org/repo.git", Map.of("text", "Ticket: fix the login bug"));

        assertThat(capturedUserMessageText()).isEqualTo("Repository: https://github.com/org/repo.git\n\nTicket: fix the login bug");
    }

    @Test
    void run_providerCallThrows_propagatesRuntimeException() {
        stubCredentialResolution();
        stubContextFactory("exec-4");
        when(chatLanguageModel.generate(anyList(), anyList())).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> runner.run(tenantId, "exec-4", AGENT_SLUG, "Hello"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void modelName_returnsConfiguredModelForTheTenantsResolvedProvider() {
        assertThat(runner.modelName(tenantId)).isEqualTo("claude-3-5-sonnet-20240620");
    }

    @Test
    void modelName_tenantResolvesToLocal_returnsLocalModelName() {
        LlmProperties localProperties = new LlmProperties("ANTHROPIC", "claude-3-5-sonnet-20240620", "llama3.1", "http://localhost:11434/v1");
        when(tenantLlmProviderResolver.resolve(tenantId)).thenReturn(LlmProvider.LOCAL);
        AgentPromptRunner localRunner = new AgentPromptRunner(vendorCredentialRepository, vendorCredentialService,
                sharedExecutionContextFactory, localProperties, tenantLlmProviderResolver, sandboxClient, mock(ToolExecutionListener.class),
                credentialResolver, agentDefinitionRepository, new ToolCatalog(List.of(new CurrentDateTimeToolFactory())),
                new InputSourceResolverRegistry(List.of(new ManualTextInputResolver())));

        assertThat(localRunner.modelName(tenantId)).isEqualTo("llama3.1");
    }
}
