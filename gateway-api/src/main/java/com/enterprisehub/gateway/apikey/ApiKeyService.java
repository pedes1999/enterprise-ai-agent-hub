package com.enterprisehub.gateway.apikey;

import com.enterprisehub.dto.ApiKeyCreatedResponse;
import com.enterprisehub.dto.ApiKeySummary;
import com.enterprisehub.gateway.entity.PlatformApiKey;
import com.enterprisehub.gateway.repository.PlatformApiKeyRepository;
import com.enterprisehub.gateway.security.ApiKeyHasher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Called only from already-authenticated requests (TenantContext is already
 * set by TenantResolvingFilter for the duration of the request), so unlike
 * AuthService there's no manual TenantContext juggling here. Reads must
 * still explicitly filter by tenantId though -- see the note on
 * PlatformApiKeyRepository for why RLS alone doesn't do it for this table.
 */
@Service
public class ApiKeyService {

    private final PlatformApiKeyRepository repository;
    private final ApiKeyHasher hasher;

    public ApiKeyService(PlatformApiKeyRepository repository, ApiKeyHasher hasher) {
        this.repository = repository;
        this.hasher = hasher;
    }

    public ApiKeyCreatedResponse create(UUID tenantId, String label) {
        String rawKey = hasher.generateRawKey();

        PlatformApiKey key = new PlatformApiKey();
        key.setTenantId(tenantId);
        key.setKeyHash(hasher.hash(rawKey));
        key.setLabel(label);
        key = repository.save(key);

        return new ApiKeyCreatedResponse(key.getId().toString(), key.getLabel(), rawKey);
    }

    public List<ApiKeySummary> list(UUID tenantId) {
        return repository.findByTenantId(tenantId).stream()
                .map(this::toSummary)
                .toList();
    }

    public void revoke(UUID tenantId, UUID keyId) {
        PlatformApiKey key = repository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(() -> new ApiKeyException(HttpStatus.NOT_FOUND, "API key not found"));

        if (key.getRevokedAt() == null) {
            key.setRevokedAt(Instant.now());
            repository.save(key);
        }
    }

    private ApiKeySummary toSummary(PlatformApiKey key) {
        return new ApiKeySummary(
                key.getId().toString(),
                key.getLabel(),
                key.getLastUsedAt(),
                key.getRevokedAt(),
                key.getCreatedAt());
    }
}
