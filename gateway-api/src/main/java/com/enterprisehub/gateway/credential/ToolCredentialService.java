package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CreateToolCredentialRequest;
import com.enterprisehub.dto.ToolCredentialSummary;
import com.enterprisehub.gateway.entity.ToolCredential;
import com.enterprisehub.gateway.repository.ToolCredentialRepository;
import com.enterprisehub.gateway.security.CredentialEncryptor;
import com.enterprisehub.gateway.security.EncryptedCredential;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Called only from ADMIN-only, already-authenticated endpoints (see
 * ToolCredentialController) plus JpaCredentialResolver (agent-runtime's
 * CredentialResolver SPI, called mid tool-execution). Same encryption
 * mechanism as VendorCredentialService -- CredentialEncryptor doesn't care
 * what kind of secret it's encrypting.
 */
@Service
public class ToolCredentialService {

    private final ToolCredentialRepository repository;
    private final CredentialEncryptor encryptor;

    public ToolCredentialService(ToolCredentialRepository repository, CredentialEncryptor encryptor) {
        this.repository = repository;
        this.encryptor = encryptor;
    }

    /** Upsert: a second call for the same kind rotates the existing credential. */
    public ToolCredentialSummary put(UUID tenantId, CreateToolCredentialRequest request) {
        ToolCredentialKind kind = ToolCredentialKind.parse(request.credentialKind())
                .orElseThrow(() -> new ToolCredentialException(HttpStatus.BAD_REQUEST,
                        "credentialKind must be one of GIT, GITHUB"));

        if (request.value() == null || request.value().isBlank()) {
            throw new ToolCredentialException(HttpStatus.BAD_REQUEST, "value is required");
        }

        EncryptedCredential encrypted = encryptor.encrypt(request.value());

        ToolCredential credential = repository.findByTenantIdAndCredentialKind(tenantId, kind.name())
                .orElseGet(ToolCredential::new);
        credential.setTenantId(tenantId);
        credential.setCredentialKind(kind.name());
        credential.setEncryptedValue(encrypted.ciphertext());
        credential.setEncryptionKeyId(encrypted.keyId());
        credential.setActive(true);
        credential.setUpdatedAt(Instant.now());

        return toSummary(repository.save(credential));
    }

    public List<ToolCredentialSummary> list(UUID tenantId) {
        return repository.findByTenantId(tenantId).stream()
                .map(this::toSummary)
                .toList();
    }

    public void delete(UUID tenantId, String kindValue) {
        ToolCredentialKind kind = ToolCredentialKind.parse(kindValue)
                .orElseThrow(() -> new ToolCredentialException(HttpStatus.BAD_REQUEST,
                        "credentialKind must be one of GIT, GITHUB"));

        ToolCredential credential = repository.findByTenantIdAndCredentialKind(tenantId, kind.name())
                .orElseThrow(() -> new ToolCredentialException(HttpStatus.NOT_FOUND,
                        "No credential stored for kind " + kind.name()));

        repository.delete(credential);
    }

    /**
     * Not exposed over HTTP -- used by JpaCredentialResolver to resolve a
     * plaintext credential at tool-execution time. Returns empty (not an
     * error) if the tenant has no active credential of this kind
     * configured; callers (a specific AgentTool) decide whether that's
     * fatal. Stamps lastUsedAt on every real resolution -- this is what
     * "actually used" means (see V11__credential_health_timestamps.sql),
     * distinct from an explicit test-connection validation.
     */
    public Optional<String> decryptActiveValue(UUID tenantId, String credentialKind) {
        return repository.findByTenantIdAndCredentialKind(tenantId, credentialKind)
                .filter(ToolCredential::isActive)
                .map(credential -> {
                    credential.setLastUsedAt(Instant.now());
                    repository.save(credential);
                    return encryptor.decrypt(new EncryptedCredential(credential.getEncryptedValue(), credential.getEncryptionKeyId()));
                });
    }

    /** Stamps lastValidatedAt after a caller (ToolCredentialTestService) has independently confirmed the credential actually works. */
    public void markValidated(UUID tenantId, String credentialKind) {
        repository.findByTenantIdAndCredentialKind(tenantId, credentialKind).ifPresent(credential -> {
            credential.setLastValidatedAt(Instant.now());
            repository.save(credential);
        });
    }

    private ToolCredentialSummary toSummary(ToolCredential credential) {
        return new ToolCredentialSummary(
                credential.getId().toString(),
                credential.getCredentialKind(),
                credential.isActive(),
                credential.getCreatedAt(),
                credential.getUpdatedAt(),
                credential.getLastUsedAt(),
                credential.getLastValidatedAt());
    }
}
