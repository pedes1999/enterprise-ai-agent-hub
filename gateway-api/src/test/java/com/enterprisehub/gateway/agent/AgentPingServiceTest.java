package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.dto.AgentPingResponse;
import com.enterprisehub.dto.AgentToolPingResponse;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.credential.VendorCredentialService;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * `ping()` is tested here directly. `pingWithTools()` is now a thin
 * wrapper around AgentPromptRunner (the actual tool-assembly/tool-calling
 * logic lives there and is tested by AgentPromptRunnerTest) -- these tests
 * only confirm the wrapping itself: prompt validation happens before
 * delegating, and AgentPromptRunner's result/exception is translated into
 * the right response/exception shape.
 */
class AgentPingServiceTest {

    private VendorCredentialRepository vendorCredentialRepository;
    private VendorCredentialService vendorCredentialService;
    private LlmEngineFactory llmEngineFactory;
    private AgentPromptRunner agentPromptRunner;
    private ChatLanguageModel chatLanguageModel;
    private AgentPingService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialRepository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        llmEngineFactory = mock(LlmEngineFactory.class);
        agentPromptRunner = mock(AgentPromptRunner.class);
        chatLanguageModel = mock(ChatLanguageModel.class);
        LlmProperties properties = new LlmProperties("ANTHROPIC", "claude-3-5-sonnet-20240620", null, null);
        when(agentPromptRunner.modelName()).thenReturn("claude-3-5-sonnet-20240620");
        service = new AgentPingService(vendorCredentialRepository, vendorCredentialService, llmEngineFactory,
                properties, agentPromptRunner);
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
        when(llmEngineFactory.create(LlmProvider.ANTHROPIC, "sk-ant-real-key", "claude-3-5-sonnet-20240620", null))
                .thenReturn(chatLanguageModel);
        when(chatLanguageModel.generate("Hello")).thenReturn("Hi there!");

        AgentPingResponse response = service.ping(tenantId, "Hello");

        assertThat(response.reply()).isEqualTo("Hi there!");
        assertThat(response.provider()).isEqualTo("ANTHROPIC");
        assertThat(response.modelName()).isEqualTo("claude-3-5-sonnet-20240620");
    }

    @Test
    void ping_neverLeaksDecryptedKeyIntoRequestToFactory_exceptAsIntendedParam() {
        VendorCredential credential = activeCredential();
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
        when(llmEngineFactory.create(any(), any(), any(), any())).thenReturn(chatLanguageModel);
        when(chatLanguageModel.generate(any(String.class))).thenReturn("ok");

        service.ping(tenantId, "Hello");

        verify(llmEngineFactory).create(LlmProvider.ANTHROPIC, "sk-ant-real-key", "claude-3-5-sonnet-20240620", null);
        verify(llmEngineFactory, never()).create(any(), eq("ciphertext"), any(), any());
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
        when(llmEngineFactory.create(any(), any(), any(), any())).thenReturn(chatLanguageModel);
        when(chatLanguageModel.generate(any(String.class))).thenThrow(new RuntimeException("401 unauthorized from Anthropic"));

        assertThatThrownBy(() -> service.ping(tenantId, "Hello"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("ANTHROPIC call failed")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ---------- pingWithTools (thin wrapper over AgentPromptRunner) ----------

    @Test
    void pingWithTools_delegatesToRunner_returnsItsResult() {
        when(agentPromptRunner.run(eq(tenantId), any(), eq(AgentPromptRunner.DEFAULT_AGENT_SLUG), eq("Hello")))
                .thenReturn(new ToolCallingChatEngine.ToolChatResult("Hi there!", false, false, null));

        AgentToolPingResponse result = service.pingWithTools(tenantId, "Hello", null);

        assertThat(result.reply()).isEqualTo("Hi there!");
        assertThat(result.toolWasUsed()).isFalse();
        assertThat(result.provider()).isEqualTo("ANTHROPIC");
        assertThat(result.modelName()).isEqualTo("claude-3-5-sonnet-20240620");
        assertThat(result.agentSlug()).isEqualTo(AgentPromptRunner.DEFAULT_AGENT_SLUG);
    }

    @Test
    void pingWithTools_explicitAgentSlug_passedThroughToRunner() {
        when(agentPromptRunner.run(eq(tenantId), any(), eq("coding-agent"), eq("Hello")))
                .thenReturn(new ToolCallingChatEngine.ToolChatResult("Hi there!", true, false, null));

        AgentToolPingResponse result = service.pingWithTools(tenantId, "Hello", "coding-agent");

        assertThat(result.agentSlug()).isEqualTo("coding-agent");
    }

    @Test
    void pingWithTools_blankPrompt_rejectedBeforeDelegating() {
        assertThatThrownBy(() -> service.pingWithTools(tenantId, " ", null))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(agentPromptRunner);
    }

    @Test
    void pingWithTools_runnerThrowsGenericRuntimeException_mapsTo502BadGateway() {
        when(agentPromptRunner.run(eq(tenantId), any(), eq(AgentPromptRunner.DEFAULT_AGENT_SLUG), eq("Hello")))
                .thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> service.pingWithTools(tenantId, "Hello", null))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void pingWithTools_runnerThrowsAgentException_statusPreserved_notRelabeledAs502() {
        when(agentPromptRunner.run(eq(tenantId), any(), eq("unknown-agent"), eq("Hello")))
                .thenThrow(new AgentException(HttpStatus.BAD_REQUEST, "Unknown or inactive agent: unknown-agent"));

        assertThatThrownBy(() -> service.pingWithTools(tenantId, "Hello", "unknown-agent"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Unknown or inactive agent")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
