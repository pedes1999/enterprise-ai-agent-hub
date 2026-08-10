package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.SharedExecutionContext;
import com.enterprisehub.core.SharedExecutionContextFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.agent.tools.CurrentDateTimeTool;
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
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialRepository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        sharedExecutionContextFactory = mock(SharedExecutionContextFactory.class);
        chatLanguageModel = mock(ChatLanguageModel.class);
        LlmProperties properties = new LlmProperties("claude-3-5-sonnet-20240620");
        SandboxClient sandboxClient = mock(SandboxClient.class);
        ToolExecutionListener toolExecutionListener = mock(ToolExecutionListener.class);
        CredentialResolver credentialResolver = mock(CredentialResolver.class);
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

    private void stubContextFactory(String executionId) {
        SharedExecutionContext context = new SharedExecutionContext(
                tenantId.toString(), executionId, chatLanguageModel, List.of(new CurrentDateTimeTool()));
        when(sharedExecutionContextFactory.create(eq(tenantId.toString()), eq(executionId), eq(LlmProvider.ANTHROPIC),
                eq("sk-ant-real-key"), eq("claude-3-5-sonnet-20240620"), any()))
                .thenReturn(context);
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
