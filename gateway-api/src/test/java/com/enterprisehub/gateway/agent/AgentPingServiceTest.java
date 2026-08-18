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
import com.enterprisehub.gateway.tenant.TenantLlmProviderResolver;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    private TenantLlmProviderResolver tenantLlmProviderResolver;
    private AgentPromptRunner agentPromptRunner;
    private ChatModel chatLanguageModel;
    private AgentPingService service;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialRepository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        llmEngineFactory = mock(LlmEngineFactory.class);
        tenantLlmProviderResolver = mock(TenantLlmProviderResolver.class);
        agentPromptRunner = mock(AgentPromptRunner.class);
        chatLanguageModel = mock(ChatModel.class);
        LlmProperties properties = new LlmProperties("ANTHROPIC", "claude-3-5-sonnet-20240620", null, null, null, null, 500_000, 100);
        when(tenantLlmProviderResolver.resolve(tenantId)).thenReturn(LlmProvider.ANTHROPIC);
        when(tenantLlmProviderResolver.resolveModelName(tenantId, LlmProvider.ANTHROPIC)).thenReturn("claude-3-5-sonnet-20240620");
        when(agentPromptRunner.modelName(tenantId)).thenReturn("claude-3-5-sonnet-20240620");
        service = new AgentPingService(vendorCredentialRepository, vendorCredentialService, llmEngineFactory,
                properties, tenantLlmProviderResolver, agentPromptRunner);
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
        when(vendorCredentialRepository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
        when(llmEngineFactory.create(LlmProvider.ANTHROPIC, "sk-ant-real-key", "claude-3-5-sonnet-20240620", null))
                .thenReturn(chatLanguageModel);
        when(chatLanguageModel.chat("Hello")).thenReturn("Hi there!");

        AgentPingResponse response = service.ping(tenantId, userId, "Hello");

        assertThat(response.reply()).isEqualTo("Hi there!");
        assertThat(response.provider()).isEqualTo("ANTHROPIC");
        assertThat(response.modelName()).isEqualTo("claude-3-5-sonnet-20240620");
    }

    @Test
    void ping_neverLeaksDecryptedKeyIntoRequestToFactory_exceptAsIntendedParam() {
        VendorCredential credential = activeCredential();
        when(vendorCredentialRepository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
        when(llmEngineFactory.create(any(), any(), any(), any())).thenReturn(chatLanguageModel);
        when(chatLanguageModel.chat(any(String.class))).thenReturn("ok");

        service.ping(tenantId, userId, "Hello");

        verify(llmEngineFactory).create(LlmProvider.ANTHROPIC, "sk-ant-real-key", "claude-3-5-sonnet-20240620", null);
        verify(llmEngineFactory, never()).create(any(), eq("ciphertext"), any(), any());
    }

    @Test
    void ping_blankPrompt_throwsBadRequest() {
        assertThatThrownBy(() -> service.ping(tenantId, userId, "  "))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(vendorCredentialRepository);
    }

    @Test
    void ping_nullPrompt_throwsBadRequest() {
        assertThatThrownBy(() -> service.ping(tenantId, userId, null)).isInstanceOf(AgentException.class);
    }

    @Test
    void ping_noCredentialConfigured_throwsBadRequestWithActionableMessage() {
        when(vendorCredentialRepository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ping(tenantId, userId, "Hello"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("PUT /vendor-credentials")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(vendorCredentialService, llmEngineFactory);
    }

    @Test
    void ping_inactiveCredential_treatedAsMissing() {
        VendorCredential credential = activeCredential();
        credential.setActive(false);
        when(vendorCredentialRepository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.ping(tenantId, userId, "Hello")).isInstanceOf(AgentException.class);
        verifyNoInteractions(vendorCredentialService);
    }

    @Test
    void ping_providerCallThrows_mapsTo502BadGateway() {
        VendorCredential credential = activeCredential();
        when(vendorCredentialRepository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
        when(llmEngineFactory.create(any(), any(), any(), any())).thenReturn(chatLanguageModel);
        when(chatLanguageModel.chat(any(String.class))).thenThrow(new RuntimeException("401 unauthorized from Anthropic"));

        assertThatThrownBy(() -> service.ping(tenantId, userId, "Hello"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("ANTHROPIC call failed")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ---------- pingWithTools (thin wrapper over AgentPromptRunner) ----------

    @Test
    void pingWithTools_delegatesToRunner_returnsItsResult() {
        when(agentPromptRunner.run(runFor(AgentPromptRunner.DEFAULT_AGENT_SLUG, "Hello")))
                .thenReturn(new ToolCallingChatEngine.ToolChatResult("Hi there!", false, false, null));

        AgentToolPingResponse result = service.pingWithTools(tenantId, userId, "Hello", null);

        assertThat(result.reply()).isEqualTo("Hi there!");
        assertThat(result.toolWasUsed()).isFalse();
        assertThat(result.provider()).isEqualTo("ANTHROPIC");
        assertThat(result.modelName()).isEqualTo("claude-3-5-sonnet-20240620");
        assertThat(result.agentSlug()).isEqualTo(AgentPromptRunner.DEFAULT_AGENT_SLUG);
    }

    @Test
    void pingWithTools_explicitAgentSlug_passedThroughToRunner() {
        when(agentPromptRunner.run(runFor("coding-agent", "Hello")))
                .thenReturn(new ToolCallingChatEngine.ToolChatResult("Hi there!", true, false, null));

        AgentToolPingResponse result = service.pingWithTools(tenantId, userId, "Hello", "coding-agent");

        assertThat(result.agentSlug()).isEqualTo("coding-agent");
    }

    @Test
    void pingWithTools_blankPrompt_rejectedBeforeDelegating() {
        assertThatThrownBy(() -> service.pingWithTools(tenantId, userId, " ", null))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(agentPromptRunner);
    }

    @Test
    void pingWithTools_runnerThrowsGenericRuntimeException_mapsTo502BadGateway() {
        when(agentPromptRunner.run(runFor(AgentPromptRunner.DEFAULT_AGENT_SLUG, "Hello")))
                .thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> service.pingWithTools(tenantId, userId, "Hello", null))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void pingWithTools_runnerThrowsAgentException_statusPreserved_notRelabeledAs502() {
        when(agentPromptRunner.run(runFor("unknown-agent", "Hello")))
                .thenThrow(new AgentException(HttpStatus.BAD_REQUEST, "Unknown or inactive agent: unknown-agent"));

        assertThatThrownBy(() -> service.pingWithTools(tenantId, userId, "Hello", "unknown-agent"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Unknown or inactive agent")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /**
     * Matches the AgentRunRequest AgentPingService builds, pinning every
     * field except executionId -- that one is a fresh random UUID minted
     * inside the service for this synchronous spike endpoint (see its
     * "synthetic id" comment), so it can't be predicted from out here.
     */
    private AgentRunRequest runFor(String agentSlug, String prompt) {
        return argThat(request -> request != null
                && tenantId.equals(request.tenantId())
                && userId.equals(request.userId())
                && agentSlug.equals(request.agentSlug())
                && prompt.equals(request.prompt()));
    }
}
