package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CreateVendorCredentialRequest;
import com.enterprisehub.dto.VendorCredentialSummary;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.gateway.security.CredentialEncryptor;
import com.enterprisehub.gateway.security.EncryptedCredential;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Called only from ADMIN-only, already-authenticated endpoints -- see
 * VendorCredentialController. Encrypted tokens are decrypted here for
 * exactly one reason: agent-core will eventually need the plaintext token
 * to construct an LLM client at execution time (Week 4-5). Nothing in this
 * class ever returns a decrypted (or even encrypted) token over HTTP --
 * VendorCredentialSummary has no token field at all.
 */
@Service
public class VendorCredentialService {

    private final VendorCredentialRepository repository;
    private final CredentialEncryptor encryptor;

    public VendorCredentialService(VendorCredentialRepository repository, CredentialEncryptor encryptor) {
        this.repository = repository;
        this.encryptor = encryptor;
    }

    /** Upsert: a second call for the same provider rotates the existing credential. */
    public VendorCredentialSummary put(UUID tenantId, CreateVendorCredentialRequest request) {
        VendorProvider provider = VendorProvider.parse(request.provider())
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.BAD_REQUEST,
                        "provider must be one of ANTHROPIC, OPENAI, GEMINI, LOCAL"));

        if (request.token() == null || request.token().isBlank()) {
            throw new VendorCredentialException(HttpStatus.BAD_REQUEST, "token is required");
        }

        EncryptedCredential encrypted = encryptor.encrypt(request.token());

        VendorCredential credential = repository.findByTenantIdAndProvider(tenantId, provider.name())
                .orElseGet(VendorCredential::new);
        credential.setTenantId(tenantId);
        credential.setProvider(provider.name());
        credential.setEncryptedToken(encrypted.ciphertext());
        credential.setEncryptionKeyId(encrypted.keyId());
        credential.setActive(true);
        credential.setUpdatedAt(Instant.now());

        return toSummary(repository.save(credential));
    }

    public List<VendorCredentialSummary> list(UUID tenantId) {
        return repository.findByTenantId(tenantId).stream()
                .map(this::toSummary)
                .toList();
    }

    public void delete(UUID tenantId, String providerValue) {
        VendorProvider provider = VendorProvider.parse(providerValue)
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.BAD_REQUEST,
                        "provider must be one of ANTHROPIC, OPENAI, GEMINI, LOCAL"));

        VendorCredential credential = repository.findByTenantIdAndProvider(tenantId, provider.name())
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.NOT_FOUND,
                        "No credential stored for provider " + provider.name()));

        repository.delete(credential);
    }

    /**
     * Not exposed over HTTP -- for agent-core's use when actually
     * constructing an LLM client for a tenant's chosen provider. Stamps
     * lastUsedAt on every real resolution -- this is what "actually used"
     * means (see V11__credential_health_timestamps.sql), distinct from an
     * explicit test-connection validation.
     */
    public String decryptToken(VendorCredential credential) {
        credential.setLastUsedAt(Instant.now());
        repository.save(credential);
        return encryptor.decrypt(new EncryptedCredential(credential.getEncryptedToken(), credential.getEncryptionKeyId()));
    }

    /** Stamps lastValidatedAt after a caller (VendorCredentialTestService) has independently confirmed the credential actually works. */
    public void markValidated(UUID tenantId, String providerValue) {
        VendorProvider.parse(providerValue).ifPresent(provider ->
                repository.findByTenantIdAndProvider(tenantId, provider.name()).ifPresent(credential -> {
                    credential.setLastValidatedAt(Instant.now());
                    repository.save(credential);
                }));
    }

    private VendorCredentialSummary toSummary(VendorCredential credential) {
        return new VendorCredentialSummary(
                credential.getId().toString(),
                credential.getProvider(),
                credential.isActive(),
                credential.getCreatedAt(),
                credential.getUpdatedAt(),
                credential.getLastUsedAt(),
                credential.getLastValidatedAt());
    }
}
