package com.enterprisehub.gateway.tenant;

import com.enterprisehub.dto.LlmProviderAvailability;
import com.enterprisehub.dto.TenantSettingsResponse;
import com.enterprisehub.dto.UpdateTenantSettingsRequest;
import com.enterprisehub.gateway.credential.VendorProvider;
import com.enterprisehub.gateway.entity.Tenant;
import com.enterprisehub.gateway.repository.TenantRepository;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Backs GET/PUT /tenant-settings -- the ADMIN-only place a tenant with
 * credentials stored for more than one provider picks which one their
 * agent executions actually use (see TenantLlmProviderResolver). Setting a
 * preference requires an active credential for that provider to already
 * exist -- same fail-closed posture as every other "no active X credential"
 * check in this codebase, just checked at preference-set time instead of
 * at run time.
 */
@Service
public class TenantSettingsService {

    private final TenantRepository tenantRepository;
    private final VendorCredentialRepository vendorCredentialRepository;
    private final TenantLlmProviderResolver tenantLlmProviderResolver;

    public TenantSettingsService(TenantRepository tenantRepository, VendorCredentialRepository vendorCredentialRepository,
                                  TenantLlmProviderResolver tenantLlmProviderResolver) {
        this.tenantRepository = tenantRepository;
        this.vendorCredentialRepository = vendorCredentialRepository;
        this.tenantLlmProviderResolver = tenantLlmProviderResolver;
    }

    public TenantSettingsResponse get(UUID tenantId) {
        Tenant tenant = requireTenant(tenantId);
        return new TenantSettingsResponse(tenant.getPreferredLlmProvider(), tenant.getPreferredModelName(),
                tenant.getMaxTokensPerExecution(), tenantLlmProviderResolver.resolveMaxTokens(tenantId), availableProviders(tenantId));
    }

    public TenantSettingsResponse update(UUID tenantId, UpdateTenantSettingsRequest request) {
        Tenant tenant = requireTenant(tenantId);
        String requestedProvider = request.preferredLlmProvider();

        if (requestedProvider == null || requestedProvider.isBlank()) {
            tenant.setPreferredLlmProvider(null);
        } else {
            VendorProvider provider = VendorProvider.parse(requestedProvider)
                    .orElseThrow(() -> new TenantSettingsException(HttpStatus.BAD_REQUEST,
                            "preferredLlmProvider must be one of ANTHROPIC, OPENAI, GEMINI, LOCAL"));

            boolean hasActiveCredential = vendorCredentialRepository.existsByTenantIdAndProviderAndActiveTrue(tenantId, provider.name());
            if (!hasActiveCredential) {
                throw new TenantSettingsException(HttpStatus.BAD_REQUEST,
                        "No active " + provider + " credential configured for this tenant -- PUT /vendor-credentials first");
            }
            tenant.setPreferredLlmProvider(provider.name());
        }

        String requestedModelName = request.preferredModelName();
        tenant.setPreferredModelName(requestedModelName == null || requestedModelName.isBlank() ? null : requestedModelName);

        Integer requestedMaxTokens = request.maxTokensPerExecution();
        if (requestedMaxTokens != null && requestedMaxTokens <= 0) {
            throw new TenantSettingsException(HttpStatus.BAD_REQUEST, "maxTokensPerExecution must be positive");
        }
        tenant.setMaxTokensPerExecution(requestedMaxTokens);

        tenantRepository.save(tenant);
        return new TenantSettingsResponse(tenant.getPreferredLlmProvider(), tenant.getPreferredModelName(),
                tenant.getMaxTokensPerExecution(), tenantLlmProviderResolver.resolveMaxTokens(tenantId), availableProviders(tenantId));
    }

    private List<LlmProviderAvailability> availableProviders(UUID tenantId) {
        return Arrays.stream(VendorProvider.values())
                .map(provider -> new LlmProviderAvailability(provider.name(),
                        vendorCredentialRepository.existsByTenantIdAndProviderAndActiveTrue(tenantId, provider.name())))
                .toList();
    }

    private Tenant requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantSettingsException(HttpStatus.NOT_FOUND, "Unknown tenant: " + tenantId));
    }
}
