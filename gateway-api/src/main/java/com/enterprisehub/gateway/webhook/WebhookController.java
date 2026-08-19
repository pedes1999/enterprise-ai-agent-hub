package com.enterprisehub.gateway.webhook;

import com.enterprisehub.dto.WebhookDeliveryAccepted;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The unauthenticated ingress SecurityConfig's {@code /webhooks/**}
 * permitAll rule has been describing since before it existed. There is no
 * PlatformPrincipal on this route and there cannot be one -- GitHub has no
 * account here. Authentication is the HMAC signature over the request body,
 * checked in WebhookIngestService before anything is read or written.
 *
 * The body is taken as {@code byte[]}, never a mapped DTO, and that is not
 * negotiable: GitHub signs the exact bytes it sent, so letting Jackson parse
 * and re-serialize first would compare a signature against different bytes
 * and reject every genuine delivery. Parsing happens later, from these same
 * bytes, once the signature has verified.
 *
 * Status codes are chosen for how they read in a repository's delivery log:
 * only a genuinely rejected delivery (bad signature, unknown endpoint,
 * unparseable payload) shows up as a failure worth investigating. A ping, an
 * action this endpoint ignores, or a redelivery of something already handled
 * are all successes -- GitHub retries 5xx and shows 4xx in red, and neither
 * is the right prompt for "we deliberately did nothing".
 *
 * Requires GitHub's webhook Content type to be set to application/json (the
 * form-urlencoded option signs a differently-framed body and is not supported).
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookIngestService ingestService;

    public WebhookController(WebhookIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/github/{endpointId}")
    public ResponseEntity<WebhookDeliveryAccepted> receiveGitHubDelivery(
            @PathVariable UUID endpointId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] rawBody) {

        WebhookIngestResult result = ingestService.ingest(endpointId, event, deliveryId, signature, rawBody);

        // 202 for a new run (same "accepted, work happens elsewhere" contract
        // as POST /agents/execute); 200 for the two do-nothing outcomes,
        // which are complete by the time this returns.
        HttpStatus status = result.outcome() == WebhookIngestResult.Outcome.QUEUED
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;

        return ResponseEntity.status(status).body(new WebhookDeliveryAccepted(
                result.outcome().name(),
                result.executionId() == null ? null : result.executionId().toString()));
    }
}
