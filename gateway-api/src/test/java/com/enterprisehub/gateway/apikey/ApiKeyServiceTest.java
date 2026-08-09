package com.enterprisehub.gateway.apikey;

import com.enterprisehub.dto.ApiKeyCreatedResponse;
import com.enterprisehub.dto.ApiKeySummary;
import com.enterprisehub.gateway.entity.PlatformApiKey;
import com.enterprisehub.gateway.repository.PlatformApiKeyRepository;
import com.enterprisehub.gateway.security.ApiKeyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiKeyServiceTest {

    private PlatformApiKeyRepository repository;
    private ApiKeyHasher hasher;
    private ApiKeyService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(PlatformApiKeyRepository.class);
        hasher = mock(ApiKeyHasher.class);
        service = new ApiKeyService(repository, hasher);
    }

    private PlatformApiKey savedKey(UUID id, UUID tenantId, String label, String hash) {
        PlatformApiKey key = new PlatformApiKey();
        key.setId(id);
        key.setTenantId(tenantId);
        key.setLabel(label);
        key.setKeyHash(hash);
        return key;
    }

    @Test
    void create_generatesAndHashesKey_returnsRawKeyExactlyOnce() {
        when(hasher.generateRawKey()).thenReturn("ahk_rawvalue");
        when(hasher.hash("ahk_rawvalue")).thenReturn("hashed-value");
        UUID keyId = UUID.randomUUID();
        when(repository.save(any(PlatformApiKey.class)))
                .thenReturn(savedKey(keyId, tenantId, "ci-key", "hashed-value"));

        ApiKeyCreatedResponse response = service.create(tenantId, "ci-key");

        assertThat(response.rawKey()).isEqualTo("ahk_rawvalue");
        assertThat(response.label()).isEqualTo("ci-key");
        assertThat(response.id()).isEqualTo(keyId.toString());

        verify(repository).save(argThat(key ->
                key.getTenantId().equals(tenantId)
                        && key.getKeyHash().equals("hashed-value")
                        && key.getLabel().equals("ci-key")));
    }

    @Test
    void create_neverPersistsTheRawKey_onlyItsHash() {
        when(hasher.generateRawKey()).thenReturn("ahk_rawvalue");
        when(hasher.hash("ahk_rawvalue")).thenReturn("hashed-value");
        when(repository.save(any(PlatformApiKey.class)))
                .thenAnswer(invocation -> {
                    PlatformApiKey key = invocation.getArgument(0);
                    key.setId(UUID.randomUUID());
                    return key;
                });

        service.create(tenantId, "label");

        verify(repository).save(argThat(key -> !"ahk_rawvalue".equals(key.getKeyHash())));
    }

    @Test
    void list_scopesToTenant_usesFindByTenantId_notFindAll() {
        PlatformApiKey key = savedKey(UUID.randomUUID(), tenantId, "ci-key", "hash1");
        key.setCreatedAt(Instant.now());
        when(repository.findByTenantId(tenantId)).thenReturn(List.of(key));

        List<ApiKeySummary> summaries = service.list(tenantId);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).label()).isEqualTo("ci-key");
        verify(repository, never()).findAll();
    }

    @Test
    void list_emptyForTenantWithNoKeys() {
        when(repository.findByTenantId(tenantId)).thenReturn(List.of());
        assertThat(service.list(tenantId)).isEmpty();
    }

    @Test
    void revoke_ownedKey_setsRevokedAt() {
        UUID keyId = UUID.randomUUID();
        PlatformApiKey key = savedKey(keyId, tenantId, "ci-key", "hash1");
        when(repository.findByIdAndTenantId(keyId, tenantId)).thenReturn(Optional.of(key));

        service.revoke(tenantId, keyId);

        verify(repository).save(argThat(saved -> saved.getRevokedAt() != null));
    }

    @Test
    void revoke_unknownOrForeignTenantKey_throwsNotFound_neverLeaksExistence() {
        UUID keyId = UUID.randomUUID();
        when(repository.findByIdAndTenantId(keyId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(tenantId, keyId))
                .isInstanceOf(ApiKeyException.class)
                .satisfies(e -> assertThat(((ApiKeyException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(repository, never()).save(any());
    }

    @Test
    void revoke_alreadyRevokedKey_isIdempotent_doesNotOverwriteTimestamp() {
        UUID keyId = UUID.randomUUID();
        PlatformApiKey key = savedKey(keyId, tenantId, "ci-key", "hash1");
        Instant originalRevokedAt = Instant.now().minusSeconds(3600);
        key.setRevokedAt(originalRevokedAt);
        when(repository.findByIdAndTenantId(keyId, tenantId)).thenReturn(Optional.of(key));

        service.revoke(tenantId, keyId);

        assertThat(key.getRevokedAt()).isEqualTo(originalRevokedAt);
        verify(repository, never()).save(any());
    }
}
