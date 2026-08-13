package com.enterprisehub.gateway.credential;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.dto.CredentialTestResult;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.gateway.tenant.TenantLlmProviderResolver;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VendorCredentialTestServiceTest {

    private VendorCredentialRepository repository;
    private VendorCredentialService vendorCredentialService;
    private LlmEngineFactory llmEngineFactory;
    private TenantLlmProviderResolver tenantLlmProviderResolver;
    private ChatLanguageModel chatModel;
    private VendorCredentialTestService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        llmEngineFactory = mock(LlmEngineFactory.class);
        tenantLlmProviderResolver = mock(TenantLlmProviderResolver.class);
        chatModel = mock(ChatLanguageModel.class);
        LlmProperties properties = new LlmProperties("ANTHROPIC", "claude-sonnet-4-5-20250929", "gpt-4o-mini", "gemini-1.5-flash", null, null, 500_000);
        service = new VendorCredentialTestService(repository, vendorCredentialService, llmEngineFactory, properties, tenantLlmProviderResolver);
        // Default stub: no tenant override, resolveModelName falls back to
        // whatever the server default would have been -- matches every
        // existing test's expectations unless a test overrides this.
        when(tenantLlmProviderResolver.resolveModelName(eq(tenantId), any()))
                .thenAnswer(invocation -> properties.modelName(invocation.getArgument(1)));
    }

    private VendorCredential activeCredential(String provider) {
        VendorCredential credential = new VendorCredential();
        credential.setProvider(provider);
        credential.setActive(true);
        return credential;
    }

    @Test
    void test_anthropic_validCredential_returnsValid_marksValidated() {
        VendorCredential credential = activeCredential("ANTHROPIC");
        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-real-key");
        when(llmEngineFactory.create(LlmProvider.ANTHROPIC, "sk-ant-real-key", "claude-sonnet-4-5-20250929", null)).thenReturn(chatModel);
        when(chatModel.generate(anyString())).thenReturn("OK");

        CredentialTestResult result = service.test(tenantId, "anthropic");

        assertThat(result.valid()).isTrue();
        verify(vendorCredentialService).markValidated(tenantId, "ANTHROPIC");
    }

    @Test
    void test_anthropic_rejectedCredential_returnsInvalid_neverMarksValidated() {
        VendorCredential credential = activeCredential("ANTHROPIC");
        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-ant-bad-key");
        when(llmEngineFactory.create(any(), any(), any(), any())).thenReturn(chatModel);
        when(chatModel.generate(anyString())).thenThrow(new RuntimeException("401 Unauthorized"));

        CredentialTestResult result = service.test(tenantId, "ANTHROPIC");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("401 Unauthorized");
        verify(vendorCredentialService, never()).markValidated(any(), any());
    }

    @Test
    void test_local_tenantHasPreferredModelName_testsAgainstThatModelNotTheServerDefault() {
        VendorCredential credential = activeCredential("LOCAL");
        when(repository.findByTenantIdAndProvider(tenantId, "LOCAL")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("not-needed");
        // Tenant pulled "llama3.1:8b" locally, not the server-wide default
        // ("llama3.1" -- see LlmProperties in setUp()) -- the test call must
        // use the tenant's actual model, or it fails against a model that
        // was never pulled even though the credential itself is fine.
        when(tenantLlmProviderResolver.resolveModelName(tenantId, LlmProvider.LOCAL)).thenReturn("llama3.1:8b");
        when(llmEngineFactory.create(LlmProvider.LOCAL, "not-needed", "llama3.1:8b", null)).thenReturn(chatModel);
        when(chatModel.generate(anyString())).thenReturn("OK");

        CredentialTestResult result = service.test(tenantId, "LOCAL");

        assertThat(result.valid()).isTrue();
        verify(llmEngineFactory).create(LlmProvider.LOCAL, "not-needed", "llama3.1:8b", null);
    }

    @Test
    void test_openai_validCredential_returnsValid_marksValidated() {
        VendorCredential credential = activeCredential("OPENAI");
        when(repository.findByTenantIdAndProvider(tenantId, "OPENAI")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("sk-openai-real-key");
        when(llmEngineFactory.create(LlmProvider.OPENAI, "sk-openai-real-key", "gpt-4o-mini", null)).thenReturn(chatModel);
        when(chatModel.generate(anyString())).thenReturn("OK");

        CredentialTestResult result = service.test(tenantId, "OPENAI");

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).contains("OPENAI");
        verify(vendorCredentialService).markValidated(tenantId, "OPENAI");
    }

    @Test
    void test_gemini_rejectedCredential_returnsInvalid() {
        VendorCredential credential = activeCredential("GEMINI");
        when(repository.findByTenantIdAndProvider(tenantId, "GEMINI")).thenReturn(Optional.of(credential));
        when(vendorCredentialService.decryptToken(credential)).thenReturn("bad-key");
        when(llmEngineFactory.create(LlmProvider.GEMINI, "bad-key", "gemini-1.5-flash", null)).thenReturn(chatModel);
        when(chatModel.generate(anyString())).thenThrow(new RuntimeException("API key not valid"));

        CredentialTestResult result = service.test(tenantId, "GEMINI");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("GEMINI").contains("API key not valid");
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
        VendorCredential credential = activeCredential("ANTHROPIC");
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
}
