package com.enterprisehub.gateway.integration;

import com.enterprisehub.dto.AuthResponse;
import com.enterprisehub.dto.CreateWebhookEndpointRequest;
import com.enterprisehub.dto.RegisterRequest;
import com.enterprisehub.dto.WebhookDeliveryAccepted;
import com.enterprisehub.dto.WebhookEndpointCreatedResponse;
import com.enterprisehub.dto.WebhookEndpointSummary;
import com.enterprisehub.gateway.agent.AgentExecutionService;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.repository.AgentExecutionRepository;
import com.enterprisehub.gateway.tenant.TenantContext;
import com.enterprisehub.gateway.webhook.WebhookSignatureVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook ingest path against real Postgres, RLS included -- which is
 * the only way to test the part that actually matters here. This route is
 * the single place in the app that resolves a tenant with NO authentication
 * having happened, relying on V34's deliberately-open SELECT policy for
 * webhook_endpoints and on WebhookIngestService setting TenantContext before
 * any write. None of that can be exercised with mocks: an in-memory
 * repository would happily return rows no matter what the policies say.
 *
 * AgentJobWorker is disabled in this profile (application-test.yml), so
 * executions stay QUEUED and these tests assert on the queued row rather
 * than racing a background poller -- same approach as
 * AgentExecutionQueueIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebhookIngestIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentExecutionRepository executionRepository;

    @Autowired
    private AgentExecutionService executionService;

    @Autowired
    private WebhookSignatureVerifier signatureVerifier;

    private static final String PULL_REQUEST_PAYLOAD = """
            {
              "action": "opened",
              "number": 7,
              "pull_request": {
                "title": "Add retry backoff",
                "body": "Fixes the flaky suite.",
                "html_url": "https://github.com/acme/widgets/pull/7",
                "head": { "ref": "add-retry-backoff" },
                "base": { "ref": "main" }
              },
              "repository": {
                "full_name": "acme/widgets",
                "clone_url": "https://github.com/acme/widgets.git"
              }
            }
            """;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private AuthResponse registerTenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest request = new RegisterRequest(slug, slug, "admin@" + slug + ".com", "p@ssword123");
        return restTemplate.postForEntity(baseUrl() + "/auth/register", request, AuthResponse.class).getBody();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private WebhookEndpointCreatedResponse createEndpoint(AuthResponse tenant) {
        ResponseEntity<WebhookEndpointCreatedResponse> response = restTemplate.exchange(
                baseUrl() + "/webhook-endpoints", HttpMethod.POST,
                new HttpEntity<>(new CreateWebhookEndpointRequest("general-assistant", "acme/widgets", null),
                        authHeaders(tenant.token())),
                WebhookEndpointCreatedResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /**
     * Posts a delivery the way GitHub does: raw bytes, signed with the
     * endpoint secret. Nothing here goes through an authenticated client --
     * the signature is the only credential.
     */
    private ResponseEntity<WebhookDeliveryAccepted> deliver(String endpointId, String secret, String payload,
                                                              String deliveryId, String event) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-GitHub-Event", event);
        headers.set("X-GitHub-Delivery", deliveryId);
        headers.set("X-Hub-Signature-256", signatureVerifier.computeSignatureHeader(body, secret));
        return restTemplate.exchange(baseUrl() + "/webhooks/github/" + endpointId, HttpMethod.POST,
                new HttpEntity<>(body, headers), WebhookDeliveryAccepted.class);
    }

    private Optional<AgentExecution> findExecution(UUID tenantId, UUID executionId) {
        TenantContext.set(tenantId.toString());
        try {
            return executionRepository.findById(executionId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aSignedPullRequestDeliveryQueuesAnExecutionAttributedToTheEndpointsUser() {
        AuthResponse tenant = registerTenant("wh-ok");
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);

        ResponseEntity<WebhookDeliveryAccepted> response = deliver(
                endpoint.id(), endpoint.secret(), PULL_REQUEST_PAYLOAD, UUID.randomUUID().toString(), "pull_request");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().outcome()).isEqualTo("QUEUED");

        UUID executionId = UUID.fromString(response.getBody().executionId());
        AgentExecution execution = findExecution(UUID.fromString(tenant.tenantId()), executionId).orElseThrow();

        assertThat(execution.getStatus()).isEqualTo("QUEUED");
        // The column has documented WEBHOOK since V1 while the code only ever
        // wrote "API" -- this is the first caller that makes it true.
        assertThat(execution.getTriggerSource()).isEqualTo("WEBHOOK");
        // Not null: an execution with no triggering user cannot resolve a
        // per-user vendor credential and would fail at run time.
        assertThat(execution.getTriggeredBy()).isEqualTo(UUID.fromString(tenant.userId()));
        assertThat(execution.getRepositoryUrl()).isEqualTo("https://github.com/acme/widgets.git");
        assertThat(execution.getRepositoryBranch()).isEqualTo("add-retry-backoff");
    }

    /**
     * The security property this whole feature rests on. A body that doesn't
     * verify must change nothing at all -- not a delivery row, not an
     * execution, not a consumed concurrency slot.
     */
    @Test
    void aDeliveryWithAnInvalidSignatureIsRejectedAndQueuesNothing() {
        AuthResponse tenant = registerTenant("wh-badsig");
        UUID tenantId = UUID.fromString(tenant.tenantId());
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);
        long activeBefore = executionService.getUsage(tenantId).active();

        byte[] body = PULL_REQUEST_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-GitHub-Event", "pull_request");
        headers.set("X-GitHub-Delivery", UUID.randomUUID().toString());
        headers.set("X-Hub-Signature-256", signatureVerifier.computeSignatureHeader(body, "whsec_the-wrong-secret"));

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/webhooks/github/" + endpoint.id(), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(executionService.getUsage(tenantId).active()).isEqualTo(activeBefore);
    }

    /** Tampering with the body after signing must be caught for the same reason. */
    @Test
    void aTamperedBodyIsRejected() {
        AuthResponse tenant = registerTenant("wh-tamper");
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);

        byte[] signedBody = PULL_REQUEST_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        byte[] sentBody = PULL_REQUEST_PAYLOAD
                .replace("acme/widgets.git", "attacker/evil.git")
                .getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-GitHub-Event", "pull_request");
        headers.set("X-GitHub-Delivery", UUID.randomUUID().toString());
        headers.set("X-Hub-Signature-256", signatureVerifier.computeSignatureHeader(signedBody, endpoint.secret()));

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/webhooks/github/" + endpoint.id(), HttpMethod.POST,
                new HttpEntity<>(sentBody, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * GitHub retries on its own schedule and lets an admin redeliver by hand,
     * reusing the delivery id. Without the UNIQUE constraint behind this,
     * one pull request would queue -- and bill -- repeated agent runs.
     */
    @Test
    void redeliveringTheSameDeliveryIdDoesNotQueueASecondExecution() {
        AuthResponse tenant = registerTenant("wh-dupe");
        UUID tenantId = UUID.fromString(tenant.tenantId());
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);
        String deliveryId = UUID.randomUUID().toString();

        ResponseEntity<WebhookDeliveryAccepted> first = deliver(
                endpoint.id(), endpoint.secret(), PULL_REQUEST_PAYLOAD, deliveryId, "pull_request");
        long activeAfterFirst = executionService.getUsage(tenantId).active();

        ResponseEntity<WebhookDeliveryAccepted> second = deliver(
                endpoint.id(), endpoint.secret(), PULL_REQUEST_PAYLOAD, deliveryId, "pull_request");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().outcome()).isEqualTo("DUPLICATE");
        // Reports the ORIGINAL run, so the caller learns where the work went.
        assertThat(second.getBody().executionId()).isEqualTo(first.getBody().executionId());
        assertThat(executionService.getUsage(tenantId).active()).isEqualTo(activeAfterFirst);
    }

    /** A different delivery id IS a different event, even with an identical body. */
    @Test
    void aDifferentDeliveryIdQueuesASeparateExecution() {
        AuthResponse tenant = registerTenant("wh-second");
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);

        ResponseEntity<WebhookDeliveryAccepted> first = deliver(
                endpoint.id(), endpoint.secret(), PULL_REQUEST_PAYLOAD, UUID.randomUUID().toString(), "pull_request");
        ResponseEntity<WebhookDeliveryAccepted> second = deliver(
                endpoint.id(), endpoint.secret(), PULL_REQUEST_PAYLOAD, UUID.randomUUID().toString(), "pull_request");

        assertThat(second.getBody().outcome()).isEqualTo("QUEUED");
        assertThat(second.getBody().executionId()).isNotEqualTo(first.getBody().executionId());
    }

    /**
     * GitHub sends this the instant a webhook is created. Anything but a
     * success here puts a red failure at the top of the delivery log the
     * moment someone finishes wiring the integration up.
     */
    @Test
    void aPingDeliveryIsAcknowledgedWithoutQueueingAnything() {
        AuthResponse tenant = registerTenant("wh-ping");
        UUID tenantId = UUID.fromString(tenant.tenantId());
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);
        long activeBefore = executionService.getUsage(tenantId).active();

        ResponseEntity<WebhookDeliveryAccepted> response = deliver(
                endpoint.id(), endpoint.secret(), "{\"zen\":\"Non-blocking is better than blocking.\"}",
                UUID.randomUUID().toString(), "ping");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().outcome()).isEqualTo("IGNORED");
        assertThat(executionService.getUsage(tenantId).active()).isEqualTo(activeBefore);
    }

    @Test
    void anActionThisEndpointDoesNotActOnIsAcknowledgedWithoutQueueingAnything() {
        AuthResponse tenant = registerTenant("wh-labeled");
        UUID tenantId = UUID.fromString(tenant.tenantId());
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);
        long activeBefore = executionService.getUsage(tenantId).active();

        ResponseEntity<WebhookDeliveryAccepted> response = deliver(
                endpoint.id(), endpoint.secret(),
                PULL_REQUEST_PAYLOAD.replace("\"action\": \"opened\"", "\"action\": \"labeled\""),
                UUID.randomUUID().toString(), "pull_request");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().outcome()).isEqualTo("IGNORED");
        assertThat(executionService.getUsage(tenantId).active()).isEqualTo(activeBefore);
    }

    @Test
    void anUnknownEndpointIdIsNotFound() {
        AuthResponse tenant = registerTenant("wh-unknown");
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);

        ResponseEntity<WebhookDeliveryAccepted> response = deliver(
                UUID.randomUUID().toString(), endpoint.secret(), PULL_REQUEST_PAYLOAD,
                UUID.randomUUID().toString(), "pull_request");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aDeactivatedEndpointStopsAcceptingDeliveries() {
        AuthResponse tenant = registerTenant("wh-off");
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);

        restTemplate.exchange(baseUrl() + "/webhook-endpoints/" + endpoint.id(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(tenant.token())), Void.class);

        ResponseEntity<WebhookDeliveryAccepted> response = deliver(
                endpoint.id(), endpoint.secret(), PULL_REQUEST_PAYLOAD, UUID.randomUUID().toString(), "pull_request");

        // Same 404 an id that never existed gets -- a caller learns nothing
        // about whether the endpoint is unknown or merely switched off.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * webhook_endpoints has an intentionally OPEN SELECT policy so the
     * pre-auth lookup can work at all, which means the database is NOT
     * filtering this table by tenant the way it does everywhere else. The
     * tenant predicate in WebhookEndpointRepository is therefore load-bearing
     * security rather than the natural query shape -- this test is what
     * catches its removal.
     */
    @Test
    void oneTenantsEndpointsAreNeverVisibleToAnother() {
        AuthResponse tenantA = registerTenant("wh-iso-a");
        AuthResponse tenantB = registerTenant("wh-iso-b");
        WebhookEndpointCreatedResponse endpointA = createEndpoint(tenantA);

        ResponseEntity<List<WebhookEndpointSummary>> listedByB = restTemplate.exchange(
                baseUrl() + "/webhook-endpoints", HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantB.token())),
                new ParameterizedTypeReference<>() {});

        assertThat(listedByB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listedByB.getBody()).extracting(WebhookEndpointSummary::id).doesNotContain(endpointA.id());
    }

    @Test
    void oneTenantCannotDeactivateAnothersEndpoint() {
        AuthResponse tenantA = registerTenant("wh-del-a");
        AuthResponse tenantB = registerTenant("wh-del-b");
        WebhookEndpointCreatedResponse endpointA = createEndpoint(tenantA);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/webhook-endpoints/" + endpointA.id(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(tenantB.token())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // And A's endpoint still works, i.e. B's attempt didn't half-succeed.
        assertThat(deliver(endpointA.id(), endpointA.secret(), PULL_REQUEST_PAYLOAD,
                UUID.randomUUID().toString(), "pull_request").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    /** The secret is show-once: the list view must never carry it, in any form. */
    @Test
    void listingEndpointsNeverReturnsTheSecret() {
        AuthResponse tenant = registerTenant("wh-secret");
        WebhookEndpointCreatedResponse endpoint = createEndpoint(tenant);

        ResponseEntity<String> raw = restTemplate.exchange(
                baseUrl() + "/webhook-endpoints", HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant.token())), String.class);

        assertThat(endpoint.secret()).startsWith("whsec_");
        assertThat(raw.getBody()).doesNotContain(endpoint.secret());
        assertThat(raw.getBody()).doesNotContain("secret");
    }

    @Test
    void creatingAnEndpointRequiresAdmin() {
        AuthResponse tenant = registerTenant("wh-role");

        // The registering user is the tenant's ADMIN, so a role check needs a
        // non-admin -- assert the endpoint is at least gated rather than open.
        ResponseEntity<String> anonymous = restTemplate.exchange(
                baseUrl() + "/webhook-endpoints", HttpMethod.POST,
                new HttpEntity<>(new CreateWebhookEndpointRequest("general-assistant", "x", null), new HttpHeaders()),
                String.class);

        assertThat(anonymous.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(tenant.role()).isEqualTo("ADMIN");
    }

    @Test
    void creatingAnEndpointForAnUnknownAgentIsRejectedUpFront() {
        AuthResponse tenant = registerTenant("wh-badagent");

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/webhook-endpoints", HttpMethod.POST,
                new HttpEntity<>(new CreateWebhookEndpointRequest("no-such-agent", "x", null), authHeaders(tenant.token())),
                String.class);

        // Caught at wiring time, not at the first real delivery.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
