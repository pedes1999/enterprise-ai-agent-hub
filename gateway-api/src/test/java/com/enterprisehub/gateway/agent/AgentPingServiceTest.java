package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.SharedExecutionContext;
import com.enterprisehub.core.SharedExecutionContextFactory;
import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.dto.AgentPingResponse;
import com.enterprisehub.dto.AgentToolPingResponse;
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

class AgentPingServiceTest {

    private VendorCredentialRepository vendorCredentialRepository;
    private VendorCredentialService vendorCredentialService;
    private LlmEngineFactory llmEngineFactory;
    private SharedExecutionContextFactory sharedExecutionContextFactory;
    private ChatLanguageModel chatLanguageModel;
    private AgentPingService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialRepository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        llmEngineFactory = mock(LlmEngineFactory.class);
        sharedExecutionContextFactory = mock(SharedExecutionContextFactory.class);
        chatLanguageModel = mock(ChatLanguageModel.class);
        LlmProperties properties = new LlmProperties("claude-3-5-sonnet-20240620");
        SandboxClient sandboxClient = mock(SandboxClient.class);
        ToolExecutionListener toolExecutionListener = mock(ToolExecutionListener.class);
        CredentialResolver credentialResolver = mock(CredentialResolver.class);
        service = new AgentPingService(vendorCredentialRepository, vendorCredentialService, llmEngineFactory,
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

    @Test
    void ping_happyPath_decryptsCredentialAndReturnsReply() {
        VendorCredential credential = activeCredential();
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
        when(llmEngineFactory.create(LlmProvider.ANTHROPIC, "sk-ant-real-key", "claude-3-5-sonnet-20240620"))
                .thenReturn(chatLanguageModel);
        when(chatLanguageModel.generate("Hello")).thenReturn("Hi there!");

        AgentPingResponse response = service.ping(tenantId, "Hello");

        assertThat(response.reply()).isEqualTo("Hi there!");
        assertThat(response.provider()).isEqualTo("ANTHROPIC");
        assertThat(response.modelName()).isEqualTo("claude-3-5-sonnet-20240620");
    }

    @Test
    void ping_neverLeaksDecryptedKeyIntoRequestToFactory_exceptAsIntendedParam() {
        // Sanity check that the factory receives the decrypted key, not the ciphertext.
        VendorCredential credential = activeCredential();
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
        when(llmEngineFactory.create(any(), any(), any())).thenReturn(chatLanguageModel);
        when(chatLanguageModel.generate(any(String.class))).thenReturn("ok");

        service.ping(tenantId, "Hello");

        verify(llmEngineFactory).create(LlmProvider.ANTHROPIC, "sk-ant-real-key", "claude-3-5-sonnet-20240620");
        verify(llmEngineFactory, never()).create(any(), eq("ciphertext"), any());
    }

    @Test
    void ping_blankPrompt_throwsBadRequest() {
        assertThatThrownBy(() -> service.ping(tenantId, "  "))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(vendorCredentialRepository);
    }

    @Test
    void ping_nullPrompt_throwsBadRequest() {
        assertThatThrownBy(() -> service.ping(tenantId, null)).isInstanceOf(AgentException.class);
    }

    @Test
    void ping_noCredentialConfigured_throwsBadRequestWithActionableMessage() {
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ping(tenantId, "Hello"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("PUT /vendor-credentials")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(vendorCredentialService, llmEngineFactory);
    }

    @Test
    void ping_inactiveCredential_treatedAsMissing() {
        VendorCredential credential = activeCredential();
        credential.setActive(false);
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.ping(tenantId, "Hello")).isInstanceOf(AgentException.class);
        verifyNoInteractions(vendorCredentialService);
    }

    @Test
    void ping_providerCallThrows_mapsTo502BadGateway() {
        VendorCredential credential = activeCredential();
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
        when(llmEngineFactory.create(any(), any(), any())).thenReturn(chatLanguageModel);
        when(chatLanguageModel.generate(any(String.class))).thenThrow(new RuntimeException("401 unauthorized from Anthropic"));

        assertThatThrownBy(() -> service.ping(tenantId, "Hello"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Anthropic API call failed")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ---------- pingWithTools ----------

    private void stubCredentialResolution() {
        VendorCredential credential = activeCredential();
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
    }

    private void stubContextFactory() {
        SharedExecutionContext context = new SharedExecutionContext(
                tenantId.toString(), "test-exec-id", chatLanguageModel, List.of(new CurrentDateTimeTool()));
        // executionId is generated internally (UUID.randomUUID()) by AgentPingService,
        // so the test can't know it in advance -- match any() for that position.
        when(sharedExecutionContextFactory.create(eq(tenantId.toString()), any(), eq(LlmProvider.ANTHROPIC),
                eq("sk-ant-real-key"), eq("claude-3-5-sonnet-20240620"), any()))
                .thenReturn(context);
    }

    @Test
    void pingWithTools_modelAnswersDirectly_noToolNeeded() {
        stubCredentialResolution();
        stubContextFactory();
        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("Hi there!")));

        AgentToolPingResponse result = service.pingWithTools(tenantId, "Hello");

        assertThat(result.reply()).isEqualTo("Hi there!");
        assertThat(result.toolWasUsed()).isFalse();
        assertThat(result.provider()).isEqualTo("ANTHROPIC");
    }

    @Test
    void pingWithTools_modelCallsTheDateTimeTool_toolWasUsedIsTrue() {
        stubCredentialResolution();
        stubContextFactory();

        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("get_current_date_time")
                .arguments("{\"timezone\":\"UTC\"}")
                .build();

        when(chatLanguageModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(toolRequest))))
                .thenReturn(Response.from(AiMessage.from("It is currently 2026-01-01T00:00:00Z")));

        AgentToolPingResponse result = service.pingWithTools(tenantId, "What time is it in UTC?");

        assertThat(result.toolWasUsed()).isTrue();
        assertThat(result.reply()).contains("2026-01-01");

        // The model must be called twice: once to decide to call the tool,
        // once more with the tool's result folded in for a final answer.
        // (ToolCallingChatEngine mutates one shared message list across both
        // calls, so comparing captured list sizes between calls isn't
        // meaningful -- see ToolCallingChatEngineTest for the message-content
        // assertion instead.)
        verify(chatLanguageModel, times(2)).generate(anyList(), anyList());
    }

    @Test
    void pingWithTools_noCredentialConfigured_throwsBadRequest() {
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pingWithTools(tenantId, "Hello"))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(sharedExecutionContextFactory);
    }

    @Test
    void pingWithTools_blankPrompt_throwsBadRequest() {
        assertThatThrownBy(() -> service.pingWithTools(tenantId, " "))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(vendorCredentialRepository, sharedExecutionContextFactory);
    }

    @Test
    void pingWithTools_providerCallThrows_mapsTo502BadGateway() {
        stubCredentialResolution();
        stubContextFactory();
        when(chatLanguageModel.generate(anyList(), anyList())).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> service.pingWithTools(tenantId, "Hello"))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
