package com.enterprisehub.gateway.tenant;

import com.enterprisehub.dto.TenantSettingsResponse;
import com.enterprisehub.dto.UpdateTenantSettingsRequest;
import com.enterprisehub.gateway.entity.Tenant;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.TenantRepository;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantSettingsServiceTest {

    private TenantRepository tenantRepository;
    private VendorCredentialRepository vendorCredentialRepository;
    private TenantLlmProviderResolver tenantLlmProviderResolver;
    private TenantSettingsService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        vendorCredentialRepository = mock(VendorCredentialRepository.class);
        tenantLlmProviderResolver = mock(TenantLlmProviderResolver.class);
        when(tenantLlmProviderResolver.resolveMaxTokens(tenantId)).thenReturn(500_000);
        service = new TenantSettingsService(tenantRepository, vendorCredentialRepository, tenantLlmProviderResolver);
    }

    private Tenant tenant() {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Acme");
        tenant.setSlug("acme");
        return tenant;
    }

    private VendorCredential activeCredential(String provider) {
        VendorCredential credential = new VendorCredential();
        credential.setId(UUID.randomUUID());
        credential.setTenantId(tenantId);
        credential.setProvider(provider);
        credential.setActive(true);
        return credential;
    }

    @Test
    void get_noPreferenceSet_returnsNullPreferenceAndAvailabilityForEveryProvider() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant()));
        when(vendorCredentialRepository.findByTenantId(tenantId)).thenReturn(List.of(activeCredential("ANTHROPIC")));

        TenantSettingsResponse response = service.get(tenantId);

        assertThat(response.preferredLlmProvider()).isNull();
        // Delegated straight to the resolver -- never null, even when
        // maxTokensPerExecution itself is (see setUp()'s stub).
        assertThat(response.effectiveMaxTokensPerExecution()).isEqualTo(500_000);
        assertThat(response.availableProviders()).hasSize(4);
        assertThat(response.availableProviders()).anySatisfy(p -> {
            assertThat(p.provider()).isEqualTo("ANTHROPIC");
            assertThat(p.hasActiveCredential()).isTrue();
        });
        assertThat(response.availableProviders()).anySatisfy(p -> {
            assertThat(p.provider()).isEqualTo("LOCAL");
            assertThat(p.hasActiveCredential()).isFalse();
        });
    }

    @Test
    void get_unknownTenant_throwsNotFound() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(tenantId))
                .isInstanceOf(TenantSettingsException.class)
                .satisfies(e -> assertThat(((TenantSettingsException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void update_validProviderWithActiveCredential_savesPreference() {
        Tenant tenant = tenant();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "LOCAL")).thenReturn(Optional.of(activeCredential("LOCAL")));
        when(vendorCredentialRepository.findByTenantId(tenantId)).thenReturn(List.of(activeCredential("LOCAL")));

        TenantSettingsResponse response = service.update(tenantId, new UpdateTenantSettingsRequest("local", null, null));

        assertThat(response.preferredLlmProvider()).isEqualTo("LOCAL");
        assertThat(tenant.getPreferredLlmProvider()).isEqualTo("LOCAL");
        verify(tenantRepository).save(tenant);
    }

    @Test
    void update_nullPreference_clearsExistingOverride() {
        Tenant tenant = tenant();
        tenant.setPreferredLlmProvider("LOCAL");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(vendorCredentialRepository.findByTenantId(tenantId)).thenReturn(List.of());

        TenantSettingsResponse response = service.update(tenantId, new UpdateTenantSettingsRequest(null, null, null));

        assertThat(response.preferredLlmProvider()).isNull();
        assertThat(tenant.getPreferredLlmProvider()).isNull();
    }

    @Test
    void update_blankPreference_clearsExistingOverride() {
        Tenant tenant = tenant();
        tenant.setPreferredLlmProvider("LOCAL");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(vendorCredentialRepository.findByTenantId(tenantId)).thenReturn(List.of());

        service.update(tenantId, new UpdateTenantSettingsRequest("  ", null, null));

        assertThat(tenant.getPreferredLlmProvider()).isNull();
    }

    @Test
    void update_unparseableProvider_throwsBadRequest_doesNotSave() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant()));

        assertThatThrownBy(() -> service.update(tenantId, new UpdateTenantSettingsRequest("COHERE", null, null)))
                .isInstanceOf(TenantSettingsException.class)
                .satisfies(e -> assertThat(((TenantSettingsException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(tenantRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void update_validProviderButNoActiveCredential_throwsBadRequestWithActionableMessage() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant()));
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "LOCAL")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(tenantId, new UpdateTenantSettingsRequest("LOCAL", null, null)))
                .isInstanceOf(TenantSettingsException.class)
                .hasMessageContaining("PUT /vendor-credentials")
                .satisfies(e -> assertThat(((TenantSettingsException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(tenantRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void update_modelNameSet_savedIndependentlyOfProvider() {
        Tenant tenant = tenant();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(vendorCredentialRepository.findByTenantId(tenantId)).thenReturn(List.of());

        TenantSettingsResponse response = service.update(tenantId, new UpdateTenantSettingsRequest(null, "claude-opus-4-1-20250805", null));

        assertThat(response.preferredModelName()).isEqualTo("claude-opus-4-1-20250805");
        assertThat(tenant.getPreferredModelName()).isEqualTo("claude-opus-4-1-20250805");
    }

    @Test
    void update_blankModelName_clearsExistingOverride() {
        Tenant tenant = tenant();
        tenant.setPreferredModelName("llama3.1");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(vendorCredentialRepository.findByTenantId(tenantId)).thenReturn(List.of());

        service.update(tenantId, new UpdateTenantSettingsRequest(null, "  ", null));

        assertThat(tenant.getPreferredModelName()).isNull();
    }

    @Test
    void update_credentialExistsButInactive_throwsBadRequest() {
        VendorCredential inactive = activeCredential("LOCAL");
        inactive.setActive(false);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant()));
        when(vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "LOCAL")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.update(tenantId, new UpdateTenantSettingsRequest("LOCAL", null, null)))
                .isInstanceOf(TenantSettingsException.class)
                .satisfies(e -> assertThat(((TenantSettingsException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void update_maxTokensSet_savedIndependentlyOfProviderAndModel() {
        Tenant tenant = tenant();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(vendorCredentialRepository.findByTenantId(tenantId)).thenReturn(List.of());

        TenantSettingsResponse response = service.update(tenantId, new UpdateTenantSettingsRequest(null, null, 50_000));

        assertThat(response.maxTokensPerExecution()).isEqualTo(50_000);
        assertThat(tenant.getMaxTokensPerExecution()).isEqualTo(50_000);
    }

    @Test
    void update_nullMaxTokens_clearsExistingOverride() {
        Tenant tenant = tenant();
        tenant.setMaxTokensPerExecution(50_000);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(vendorCredentialRepository.findByTenantId(tenantId)).thenReturn(List.of());

        service.update(tenantId, new UpdateTenantSettingsRequest(null, null, null));

        assertThat(tenant.getMaxTokensPerExecution()).isNull();
    }

    @Test
    void update_zeroOrNegativeMaxTokens_throwsBadRequest_doesNotSave() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant()));

        assertThatThrownBy(() -> service.update(tenantId, new UpdateTenantSettingsRequest(null, null, 0)))
                .isInstanceOf(TenantSettingsException.class)
                .satisfies(e -> assertThat(((TenantSettingsException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.update(tenantId, new UpdateTenantSettingsRequest(null, null, -1)))
                .isInstanceOf(TenantSettingsException.class);
        verify(tenantRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }
}
