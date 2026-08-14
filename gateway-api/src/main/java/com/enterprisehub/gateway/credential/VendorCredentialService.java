package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CreateVendorCredentialRequest;
import com.enterprisehub.dto.TeamVendorCredentialSummary;
import com.enterprisehub.dto.VendorCredentialSummary;
import com.enterprisehub.gateway.entity.AppUser;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.AppUserRepository;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.gateway.security.CredentialEncryptor;
import com.enterprisehub.gateway.security.EncryptedCredential;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Called only from already-authenticated endpoints -- see
 * VendorCredentialController. Encrypted tokens are decrypted here for
 * exactly one reason: agent-core needs the plaintext token to construct an
 * LLM client at execution time. Nothing in this class ever returns a
 * decrypted (or even encrypted) token over HTTP -- VendorCredentialSummary/
 * TeamVendorCredentialSummary have no token field at all.
 *
 * Per-user, not per-tenant (see V22__vendor_credentials_per_user.sql): every
 * method except listForTeam()/deactivateForUser() (the ADMIN-only,
 * cross-user views) is scoped to one caller's own credential. There is
 * deliberately no "tenant default" fallback -- a user with no credential
 * for a provider simply can't use it, see AgentPromptRunner.resolveApiKey().
 */
@Service
public class VendorCredentialService {

    private final VendorCredentialRepository repository;
    private final AppUserRepository appUserRepository;
    private final CredentialEncryptor encryptor;

    public VendorCredentialService(VendorCredentialRepository repository, AppUserRepository appUserRepository, CredentialEncryptor encryptor) {
        this.repository = repository;
        this.appUserRepository = appUserRepository;
        this.encryptor = encryptor;
    }

    /** Upsert: a second call for the same provider (by the same user) rotates their existing credential. */
    public VendorCredentialSummary put(UUID tenantId, UUID userId, CreateVendorCredentialRequest request) {
        VendorProvider provider = VendorProvider.parse(request.provider())
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.BAD_REQUEST,
                        "provider must be one of ANTHROPIC, OPENAI, GEMINI, LOCAL"));

        if (request.token() == null || request.token().isBlank()) {
            throw new VendorCredentialException(HttpStatus.BAD_REQUEST, "token is required");
        }

        EncryptedCredential encrypted = encryptor.encrypt(request.token());

        VendorCredential credential = repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, provider.name())
                .orElseGet(VendorCredential::new);
        credential.setTenantId(tenantId);
        credential.setUserId(userId);
        credential.setProvider(provider.name());
        credential.setEncryptedToken(encrypted.ciphertext());
        credential.setEncryptionKeyId(encrypted.keyId());
        credential.setActive(true);
        credential.setUpdatedAt(Instant.now());

        return toSummary(repository.save(credential));
    }

    /** The caller's own credentials -- backs GET /vendor-credentials ("my vendor credentials"). */
    public List<VendorCredentialSummary> list(UUID tenantId, UUID userId) {
        return repository.findByTenantIdAndUserId(tenantId, userId).stream()
                .map(this::toSummary)
                .toList();
    }

    public void delete(UUID tenantId, UUID userId, String providerValue) {
        VendorProvider provider = VendorProvider.parse(providerValue)
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.BAD_REQUEST,
                        "provider must be one of ANTHROPIC, OPENAI, GEMINI, LOCAL"));

        VendorCredential credential = repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, provider.name())
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.NOT_FOUND,
                        "No credential stored for provider " + provider.name()));

        repository.delete(credential);
    }

    /**
     * Not exposed over HTTP -- for agent-core's use when actually
     * constructing an LLM client for the triggering user's chosen provider.
     * Stamps lastUsedAt on every real resolution -- this is what "actually
     * used" means (see V11__credential_health_timestamps.sql), distinct
     * from an explicit test-connection validation.
     */
    public String decryptToken(VendorCredential credential) {
        credential.setLastUsedAt(Instant.now());
        repository.save(credential);
        return encryptor.decrypt(new EncryptedCredential(credential.getEncryptedToken(), credential.getEncryptionKeyId()));
    }

    /** Stamps lastValidatedAt after a caller (VendorCredentialTestService) has independently confirmed their own credential actually works. */
    public void markValidated(UUID tenantId, UUID userId, String providerValue) {
        VendorProvider.parse(providerValue).ifPresent(provider ->
                repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, provider.name()).ifPresent(credential -> {
                    credential.setLastValidatedAt(Instant.now());
                    repository.save(credential);
                }));
    }

    /**
     * ADMIN-only cross-user view: every credential across the whole tenant,
     * with owner email attached, no secret ever touched. Backs GET
     * /vendor-credentials/team.
     *
     * Sorted by owner email, then provider -- repository.findByTenantId()
     * has no defined order of its own (effectively insertion order), which
     * interleaves different users' rows depending on who connected what
     * when. The frontend groups this list by user for display (see
     * Credentials' groupedTeamCredentials), so a stable per-user ordering
     * here is what makes each group's own rows land in a consistent order
     * too, not just the groups themselves.
     */
    public List<TeamVendorCredentialSummary> listForTeam(UUID tenantId) {
        List<VendorCredential> credentials = repository.findByTenantId(tenantId);
        Map<UUID, String> emailsByUserId = appUserRepository.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getEmail));

        return credentials.stream()
                .map(credential -> new TeamVendorCredentialSummary(
                        credential.getUserId().toString(),
                        emailsByUserId.getOrDefault(credential.getUserId(), "(unknown user)"),
                        credential.getProvider(),
                        credential.isActive(),
                        credential.getLastUsedAt(),
                        credential.getLastValidatedAt()))
                .sorted(Comparator.comparing(TeamVendorCredentialSummary::userEmail)
                        .thenComparing(TeamVendorCredentialSummary::provider))
                .toList();
    }

    /**
     * ADMIN-only, blind: flips a specific user's credential to inactive
     * without ever reading encryptedToken -- e.g. offboarding someone who
     * left, without needing their cooperation or seeing their key. Distinct
     * from delete() (self-service, removes the row entirely) -- this just
     * disables it, same "active" flag every resolution already checks.
     */
    public void deactivateForUser(UUID tenantId, UUID targetUserId, String providerValue) {
        VendorProvider provider = VendorProvider.parse(providerValue)
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.BAD_REQUEST,
                        "provider must be one of ANTHROPIC, OPENAI, GEMINI, LOCAL"));

        VendorCredential credential = repository.findByTenantIdAndUserIdAndProvider(tenantId, targetUserId, provider.name())
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.NOT_FOUND,
                        "No credential stored for that user/provider"));

        credential.setActive(false);
        credential.setUpdatedAt(Instant.now());
        repository.save(credential);
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
