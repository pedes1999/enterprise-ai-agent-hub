package com.enterprisehub.gateway.webhook;

import com.enterprisehub.gateway.agent.EnqueueExecutionCommand;
import com.enterprisehub.gateway.entity.WebhookEndpoint;
import com.enterprisehub.gateway.repository.WebhookDeliveryRepository;
import com.enterprisehub.gateway.repository.WebhookEndpointRepository;
import com.enterprisehub.gateway.security.CredentialEncryptor;
import com.enterprisehub.gateway.security.EncryptedCredential;
import com.enterprisehub.gateway.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * The one place in this application that has to establish who a caller is
 * WITHOUT any authentication having happened.
 *
 * Every other endpoint gets a tenant for free: JwtAuthFilter authenticates,
 * TenantResolvingFilter reads the tenant off the resolved PlatformPrincipal,
 * and TenantAwareDataSource has app.current_tenant_id set before the handler
 * runs. A GitHub webhook carries none of that -- no JWT, no API key, no
 * session. All it carries is the endpoint id in its own URL and an HMAC
 * signature over its body.
 *
 * So the order of operations below is the security design, not incidental:
 *
 *   1. Look the endpoint up with NO tenant context. V34's
 *      webhook_endpoints_lookup_by_id policy (FOR SELECT USING (true))
 *      permits precisely this one query, for the same reason
 *      platform_api_keys allows an unscoped lookup by hash: discovering the
 *      tenant IS the purpose of the query, so it cannot itself be scoped by
 *      tenant. Both are safe because the thing being matched -- a random
 *      UUID, a key hash -- is unguessable.
 *
 *   2. Only now set TenantContext, from the row, never from anything the
 *      caller supplied. From here on RLS is doing its normal job again.
 *
 *   3. Verify the signature BEFORE parsing the body, and before writing
 *      anything at all. An unverified payload is attacker-controlled input;
 *      it does not get to reach Jackson, the agent definition lookup, or the
 *      queue.
 *
 *   4. Hand off to WebhookDeliveryRecorder for the writes -- a separate bean
 *      so the transaction starts AFTER step 2 (see its javadoc; sharing a
 *      transaction with step 1 would reuse a connection pinned to an empty
 *      tenant).
 *
 * TenantContext is cleared in a finally here as well as in
 * TenantResolvingFilter: this method sets it, so this method is responsible
 * for it, rather than depending on a filter further up the stack that was
 * written for the authenticated case.
 */
@Service
public class WebhookIngestService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestService.class);

    /**
     * GitHub sends this once, immediately, when a webhook is first created.
     * It carries no pull_request payload. Answering it with anything other
     * than a success would put a red failure at the top of the delivery log
     * the moment someone finishes setting the integration up.
     */
    private static final String PING_EVENT = "ping";

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryRecorder deliveryRecorder;
    private final WebhookSignatureVerifier signatureVerifier;
    private final GitHubEventMapper eventMapper;
    private final CredentialEncryptor encryptor;
    private final ObjectMapper objectMapper;

    public WebhookIngestService(WebhookEndpointRepository endpointRepository,
                                 WebhookDeliveryRepository deliveryRepository,
                                 WebhookDeliveryRecorder deliveryRecorder,
                                 WebhookSignatureVerifier signatureVerifier,
                                 GitHubEventMapper eventMapper,
                                 CredentialEncryptor encryptor,
                                 ObjectMapper objectMapper) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryRecorder = deliveryRecorder;
        this.signatureVerifier = signatureVerifier;
        this.eventMapper = eventMapper;
        this.encryptor = encryptor;
        this.objectMapper = objectMapper;
    }

    public WebhookIngestResult ingest(UUID endpointId, String eventType, String deliveryId,
                                       String signatureHeader, byte[] rawBody) {
        // Step 1 -- no tenant context yet, by necessity.
        WebhookEndpoint endpoint = endpointRepository.findByIdAndActiveTrue(endpointId)
                .orElseThrow(() -> new WebhookException(HttpStatus.NOT_FOUND, "No such webhook endpoint"));

        try {
            // Step 2 -- from the row, never from caller input.
            TenantContext.set(endpoint.getTenantId().toString());

            // Step 3 -- nothing below this line trusts the body until it passes.
            requireValidSignature(endpoint, signatureHeader, rawBody);

            if (PING_EVENT.equals(eventType)) {
                log.info("Webhook endpoint {} verified by GitHub ping", endpointId);
                return WebhookIngestResult.ignored();
            }
            if (eventType == null || !eventType.equals(endpoint.getEventType())) {
                // Subscribed to more events in GitHub than this endpoint acts
                // on. Acknowledge rather than fail -- it's a configuration
                // choice on GitHub's side, not an error on ours.
                return WebhookIngestResult.ignored();
            }
            if (deliveryId == null || deliveryId.isBlank()) {
                // Every genuine GitHub delivery carries one, and without it
                // there is no idempotency key -- so this is refused rather
                // than run at the risk of duplicate billing.
                throw new WebhookException(HttpStatus.BAD_REQUEST, "X-GitHub-Delivery header is required");
            }

            // Cheap pre-check for the common redelivery case, so the usual
            // duplicate never has to go through a rolled-back transaction.
            // Not sufficient on its own -- two simultaneous deliveries can
            // both pass it -- which is why the UNIQUE constraint is still the
            // actual guarantee and is handled below.
            Optional<WebhookIngestResult> alreadySeen = findExistingDelivery(endpointId, deliveryId);
            if (alreadySeen.isPresent()) {
                return alreadySeen.get();
            }

            Optional<EnqueueExecutionCommand> command = eventMapper.toCommand(parse(rawBody), endpoint);
            if (command.isEmpty()) {
                return WebhookIngestResult.ignored();
            }

            // Step 4 -- the writes, in their own transaction.
            try {
                UUID executionId = deliveryRecorder.recordAndEnqueue(endpoint, deliveryId, command.get());
                log.info("Webhook delivery {} on endpoint {} queued execution {}", deliveryId, endpointId, executionId);
                return WebhookIngestResult.queued(executionId);
            } catch (DataIntegrityViolationException e) {
                // Lost the race against a concurrent copy of the same
                // delivery. The other one won and its execution is the real
                // one; this transaction rolled back entirely, so there is no
                // orphaned run to clean up.
                return findExistingDelivery(endpointId, deliveryId)
                        .orElseThrow(() -> new WebhookException(HttpStatus.CONFLICT,
                                "Delivery could not be recorded"));
            }
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<WebhookIngestResult> findExistingDelivery(UUID endpointId, String deliveryId) {
        return deliveryRepository.findByEndpointIdAndDeliveryId(endpointId, deliveryId)
                .map(existing -> {
                    log.info("Webhook delivery {} on endpoint {} already handled by execution {} -- not re-running",
                            deliveryId, endpointId, existing.getExecutionId());
                    return WebhookIngestResult.duplicate(existing.getExecutionId());
                });
    }

    private void requireValidSignature(WebhookEndpoint endpoint, String signatureHeader, byte[] rawBody) {
        String secret = encryptor.decrypt(new EncryptedCredential(endpoint.getSecretCiphertext(), endpoint.getSecretKeyId()));
        if (!signatureVerifier.isValid(rawBody, secret, signatureHeader)) {
            // Logged without the supplied signature or any part of the
            // expected one: the expected digest is as good as the secret for
            // forging this one request, and log access should not hand that
            // over. The endpoint id is enough to investigate with.
            log.warn("Rejected webhook delivery for endpoint {} -- signature did not verify", endpoint.getId());
            throw new WebhookException(HttpStatus.UNAUTHORIZED, "Signature verification failed");
        }
    }

    private GitHubPullRequestEvent parse(byte[] rawBody) {
        try {
            return objectMapper.readValue(rawBody, GitHubPullRequestEvent.class);
        } catch (Exception e) {
            // Reached only for a body that verified against the endpoint
            // secret, so this is a payload shape mismatch rather than junk
            // from an unauthenticated caller. The exception message is not
            // echoed back -- it can quote the body.
            throw new WebhookException(HttpStatus.BAD_REQUEST, "Could not parse the pull_request payload");
        }
    }
}
