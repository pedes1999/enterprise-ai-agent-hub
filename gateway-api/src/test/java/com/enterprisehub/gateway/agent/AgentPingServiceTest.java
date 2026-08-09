package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.dto.AgentPingResponse;
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

class AgentPingServiceTest {

    private VendorCredentialRepository vendorCredentialRepository;
    private VendorCredentialService vendorCredentialService;
    private LlmEngineFactory llmEngineFactory;
    private ChatLanguageModel chatLanguageModel;
    private AgentPingService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialRepository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        llmEngineFactory = mock(LlmEngineFactory.class);
        chatLanguageModel = mock(ChatLanguageModel.class);
        LlmProperties properties = new LlmProperties("claude-3-5-sonnet-20240620");
        service = new AgentPingService(vendorCredentialRepository, vendorCredentialService, llmEngineFactory, properties);
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
}
