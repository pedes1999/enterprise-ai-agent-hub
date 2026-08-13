package com.enterprisehub.gateway.credential;

import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The actual outbound HTTP calls to each vendor's real model-listing API
 * are deliberately NOT unit tested here (would need an HTTP stub server
 * or an injectable client, neither of which exists yet) -- these tests
 * cover the credential-resolution guard, which every one of those calls
 * sits behind and which never reaches the network on failure.
 */
class VendorModelCatalogServiceTest {

    private VendorCredentialRepository vendorCredentialRepository;
    private VendorCredentialService vendorCredentialService;
    private VendorModelCatalogService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialRepository = mock(VendorCredentialRepository.class);
        vendorCredentialService = mock(VendorCredentialService.class);
        LlmProperties llmProperties = new LlmProperties("ANTHROPIC", "claude-3-5-sonnet-20240620", null, null, null, null, 500_000, 100);
        service = new VendorModelCatalogService(vendorCredentialRepository, vendorCredentialService, llmProperties);
    }

    @Test
    void list_invalidProvider_throwsBadRequest_neverResolvesCredential() {
        assertThatThrownBy(() -> service.list(tenantId, "COHERE"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(vendorCredentialRepository, vendorCredentialService);
    }

    @Test
    void list_noCredentialConfigured_throwsNotFound() {
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(tenantId, "ANTHROPIC"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(vendorCredentialService);
    }

    @Test
    void list_inactiveCredential_treatedAsMissing() {
        VendorCredential inactive = new VendorCredential();
        inactive.setProvider("ANTHROPIC");
        inactive.setActive(false);
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.list(tenantId, "ANTHROPIC"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(vendorCredentialService);
    }
}
