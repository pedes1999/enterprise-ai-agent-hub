package com.enterprisehub.gateway.credential;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.dto.CredentialTestResult;
import com.enterprisehub.gateway.config.LlmProperties;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VendorCredentialTestServiceTest {

    private VendorCredentialRepository repository;
    private VendorCredentialService vendorCredentialService;
    private LlmEngineFactory llmEngineFactory;
    private ChatLanguageModel chatModel;
    private VendorCredentialTestService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        llmEngineFactory = mock(LlmEngineFactory.class);
        chatModel = mock(ChatLanguageModel.class);
        LlmProperties properties = new LlmProperties("claude-sonnet-4-5-20250929");
        service = new VendorCredentialTestService(repository, vendorCredentialService, llmEngineFactory, properties);
    }

    private VendorCredential activeCredential() {
        VendorCredential credential = new VendorCredential();
        credential.setProvider("ANTHROPIC");
        credential.setActive(true);
        return credential;
    }

    @Test
    void test_anthropic_validCredential_returnsValid_marksValidated() {
        VendorCredential credential = activeCredential();
        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
        when(llmEngineFactory.create(LlmProvider.ANTHROPIC, "sk-ant-real-key", "claude-sonnet-4-5-20250929")).thenReturn(chatModel);
        when(chatModel.generate(anyString())).thenReturn("OK");

        CredentialTestResult result = service.test(tenantId, "anthropic");

        assertThat(result.valid()).isTrue();
        verify(vendorCredentialService).markValidated(tenantId, "ANTHROPIC");
    }

    @Test
    void test_anthropic_rejectedCredential_returnsInvalid_neverMarksValidated() {
        VendorCredential credential = activeCredential();
        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-bad-key");
        when(llmEngineFactory.create(any(), any(), any())).thenReturn(chatModel);
        when(chatModel.generate(anyString())).thenThrow(new RuntimeException("401 Unauthorized"));

        CredentialTestResult result = service.test(tenantId, "ANTHROPIC");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("401 Unauthorized");
        verify(vendorCredentialService, never()).markValidated(any(), any());
    }

    @Test
    void test_noCredentialStored_throwsNotFound() {
        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.test(tenantId, "ANTHROPIC"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void test_inactiveCredential_throwsNotFound() {
        VendorCredential credential = activeCredential();
        credential.setActive(false);
        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.test(tenantId, "ANTHROPIC"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void test_invalidProvider_throwsBadRequest() {
        assertThatThrownBy(() -> service.test(tenantId, "COHERE"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void test_openaiProvider_notSupportedYet_neverCallsLlmFactory() {
        CredentialTestResult result = service.test(tenantId, "OPENAI");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("not supported");
        verifyNoInteractions(llmEngineFactory);
        verifyNoInteractions(vendorCredentialService);
    }
}
