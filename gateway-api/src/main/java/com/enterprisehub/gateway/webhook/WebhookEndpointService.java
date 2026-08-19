package com.enterprisehub.gateway.webhook;

import com.enterprisehub.dto.CreateWebhookEndpointRequest;
import com.enterprisehub.dto.WebhookEndpointCreatedResponse;
import com.enterprisehub.dto.WebhookEndpointSummary;
import com.enterprisehub.gateway.config.WebhookProperties;
import com.enterprisehub.gateway.entity.WebhookEndpoint;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.gateway.repository.AppUserRepository;
import com.enterprisehub.gateway.repository.WebhookEndpointRepository;
import com.enterprisehub.gateway.security.CredentialEncryptor;
import com.enterprisehub.gateway.security.EncryptedCredential;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Managing the wiring, as opposed to WebhookIngestService which handles
 * traffic arriving on it. Called only from authenticated ADMIN requests, so
 * TenantContext is already set by TenantResolvingFilter.
 *
 * Reads here still filter by tenantId explicitly. That is not the usual
 * belt-and-braces: webhook_endpoints has an open SELECT policy (V34), so
 * unlike almost every other table in this schema the database is NOT
 * filtering it by tenant, and an unfiltered findAll() would genuinely return
 * other tenants' endpoints. Same discipline PlatformApiKeyRepository requires
 * for the same reason.
 */
@Service
public class WebhookEndpointService {

    /**
     * Prefix so a leaked value is recognizable as a webhook secret in a log
     * or a paste, the way "ahk_" marks a platform API key. 32 random bytes
     * is far past what an HMAC key needs, and costs nothing.
     */
    private static final String SECRET_PREFIX = "whsec_";
    private static final int SECRET_BYTES = 32;

    private final WebhookEndpointRepository repository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final AppUserRepository appUserRepository;
    private final CredentialEncryptor encryptor;
    private final WebhookProperties webhookProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebhookEndpointService(WebhookEndpointRepository repository,
                                   AgentDefinitionRepository agentDefinitionRepository,
                                   AppUserRepository appUserRepository,
                                   CredentialEncryptor encryptor,
                                   WebhookProperties webhookProperties) {
        this.repository = repository;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.appUserRepository = appUserRepository;
        this.encryptor = encryptor;
        this.webhookProperties = webhookProperties;
    }

    @Transactional
    public WebhookEndpointCreatedResponse create(UUID tenantId, UUID creatingUserId, CreateWebhookEndpointRequest request) {
        String agentSlug = request.agentSlug();
        if (agentSlug == null || agentSlug.isBlank()) {
            throw new WebhookException(HttpStatus.BAD_REQUEST, "agentSlug is required");
        }
        // Fail here rather than at delivery time: a typo'd slug would
        // otherwise sit dormant until GitHub sends a real event and every
        // delivery fails, which is a much worse place to discover it.
        agentDefinitionRepository.findBySlugAndActiveTrue(agentSlug)
                .orElseThrow(() -> new WebhookException(HttpStatus.BAD_REQUEST, "Unknown or inactive agent: " + agentSlug));

        UUID runAsUserId = resolveRunAsUser(creatingUserId, request.runAsUserId());

        String secret = generateSecret();
        EncryptedCredential encrypted = encryptor.encrypt(secret);

        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setTenantId(tenantId);
        endpoint.setAgentSlug(agentSlug);
        endpoint.setRunAsUserId(runAsUserId);
        endpoint.setSecretCiphertext(encrypted.ciphertext());
        endpoint.setSecretKeyId(encrypted.keyId());
        endpoint.setLabel(request.label());
        endpoint = repository.save(endpoint);

        // The only time the plaintext secret leaves this method. Nothing logs
        // it, and no later read can recover it -- rotating means creating a
        // new endpoint, same contract as a platform API key.
        return new WebhookEndpointCreatedResponse(
                endpoint.getId().toString(),
                endpoint.getAgentSlug(),
                endpoint.getLabel(),
                deliveryUrl(endpoint.getId()),
                secret);
    }

    public List<WebhookEndpointSummary> list(UUID tenantId) {
        return repository.findByTenantIdAndActiveTrueOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Soft delete. The row stays so its webhook_deliveries history (and the
     * FK from it) survives -- and so a delivery arriving after deactivation
     * gets the same 404 as an id that never existed, rather than a dangling
     * reference.
     */
    @Transactional
    public void deactivate(UUID tenantId, UUID endpointId) {
        WebhookEndpoint endpoint = repository.findByIdAndTenantId(endpointId, tenantId)
                .orElseThrow(() -> new WebhookException(HttpStatus.NOT_FOUND, "Webhook endpoint not found"));
        if (endpoint.isActive()) {
            endpoint.setActive(false);
            repository.save(endpoint);
        }
    }

    /**
     * Defaults to the admin making the request. An explicit runAsUserId is
     * checked against app_users, whose RLS SELECT policy is closed and
     * therefore already tenant-scoped -- so a user from another tenant simply
     * isn't found, and cannot be borrowed to spend someone else's credential.
     */
    private UUID resolveRunAsUser(UUID creatingUserId, String requestedRunAsUserId) {
        if (requestedRunAsUserId == null || requestedRunAsUserId.isBlank()) {
            return creatingUserId;
        }
        UUID runAsUserId;
        try {
            runAsUserId = UUID.fromString(requestedRunAsUserId);
        } catch (IllegalArgumentException e) {
            throw new WebhookException(HttpStatus.BAD_REQUEST, "runAsUserId is not a valid id");
        }
        if (appUserRepository.findById(runAsUserId).isEmpty()) {
            throw new WebhookException(HttpStatus.BAD_REQUEST, "No such user in this tenant: " + runAsUserId);
        }
        return runAsUserId;
    }

    private String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String deliveryUrl(UUID endpointId) {
        String base = webhookProperties.publicBaseUrl();
        String trimmed = (base == null || base.isBlank()) ? "" : base.replaceAll("/+$", "");
        return trimmed + "/webhooks/github/" + endpointId;
    }

    private WebhookEndpointSummary toSummary(WebhookEndpoint endpoint) {
        return new WebhookEndpointSummary(
                endpoint.getId().toString(),
                endpoint.getAgentSlug(),
                endpoint.getLabel(),
                endpoint.getEventType(),
                endpoint.getRunAsUserId().toString(),
                deliveryUrl(endpoint.getId()),
                endpoint.getCreatedAt());
    }
}
