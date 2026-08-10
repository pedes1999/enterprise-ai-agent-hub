package com.enterprisehub.gateway.integration;

import com.enterprisehub.dto.AgentExecutionAccepted;
import com.enterprisehub.dto.AgentExecutionStatusResponse;
import com.enterprisehub.dto.AuthResponse;
import com.enterprisehub.dto.RegisterRequest;
import com.enterprisehub.dto.TriggerAgentExecutionRequest;
import com.enterprisehub.gateway.agent.AgentExecutionService;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the DB-backed job queue's real moving parts against real Postgres
 * (RLS included): POST /agents/execute enqueues QUEUED and returns
 * immediately (AgentJobWorker is disabled in this profile -- see
 * application-test.yml -- so this test drives the claim/complete lifecycle
 * itself via AgentExecutionService, exactly what AgentJobWorker's poll
 * loop would do); the RLS worker-sentinel carve-out from
 * V5__agent_execution_queue.sql actually lets a claim query see a job
 * regardless of which tenant created it, but nothing else can; and GET
 * /agents/executions/{id} is properly tenant-isolated. Doesn't exercise
 * AgentPromptRunner/a real Anthropic call -- that's AgentPromptRunnerTest's
 * job with mocks, and RunShellCommandToolManualIT/GitCloneToolTest's job
 * for real infra.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgentExecutionQueueIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentExecutionService executionService;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private AuthResponse registerTenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest request = new RegisterRequest(slug, slug, "admin@" + slug + ".com", "password123");
        return restTemplate.postForEntity(baseUrl() + "/auth/register", request, AuthResponse.class).getBody();
    }

    /**
     * Claims jobs one at a time (exactly as AgentJobWorker would) until it
     * finds targetId, marking any other claimed job SUCCEEDED along the
     * way so it doesn't stay QUEUED/RUNNING and pollute later test runs.
     */
    private AgentExecution drainUntilClaimed(UUID targetId) {
        for (int attempt = 0; attempt < 50; attempt++) {
            TenantContext.set(TenantContext.SYSTEM_WORKER_TENANT_ID);
            Optional<AgentExecution> claimed;
            try {
                claimed = executionService.claimNext();
            } finally {
                TenantContext.clear();
            }
            AgentExecution job = claimed.orElseThrow(() ->
                    new AssertionError("Ran out of queued jobs before finding " + targetId));
            if (job.getId().equals(targetId)) {
                return job;
            }
            TenantContext.set(job.getTenantId().toString());
            try {
                executionService.complete(job.getId(), "(drained by an unrelated test)", false);
            } finally {
                TenantContext.clear();
            }
        }
        throw new AssertionError("Drained 50 jobs without finding " + targetId);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void execute_thenGet_staysQueuedUntilSomethingProcessesIt() {
        AuthResponse tenant = registerTenant("job-a");

        ResponseEntity<AgentExecutionAccepted> postResponse = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("list files", null), authHeaders(tenant.token())),
                AgentExecutionAccepted.class);

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(postResponse.getBody().status()).isEqualTo("QUEUED");
        UUID executionId = postResponse.getBody().executionId();

        ResponseEntity<AgentExecutionStatusResponse> getResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant.token())), AgentExecutionStatusResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().status()).isEqualTo("QUEUED");
        assertThat(getResponse.getBody().prompt()).isEqualTo("list files");
        assertThat(getResponse.getBody().reply()).isNull();
    }

    @Test
    void fullLifecycle_enqueueClaimComplete_visibleThroughGetEndpoint() {
        AuthResponse tenant = registerTenant("job-b");

        ResponseEntity<AgentExecutionAccepted> postResponse = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("list files", null), authHeaders(tenant.token())),
                AgentExecutionAccepted.class);
        UUID executionId = postResponse.getBody().executionId();

        // Simulate exactly what AgentJobWorker.pollAndProcessOne() does,
        // without needing a real LLM call: claim under the worker
        // sentinel, then complete as if AgentPromptRunner had succeeded.
        // Other test methods in this class also enqueue jobs (deliberately
        // -- that's what proves cross-tenant claim visibility) and this
        // suite reuses one real DB across methods, so claimNextQueued's
        // FIFO order may hand back an unrelated leftover job first;
        // drainUntilClaimed keeps claiming (harmlessly completing anything
        // that isn't ours) until it finds this specific execution.
        AgentExecution claimed = drainUntilClaimed(executionId);
        assertThat(claimed.getId()).isEqualTo(executionId);
        assertThat(claimed.getStatus()).isEqualTo("RUNNING");

        TenantContext.set(tenant.tenantId());
        try {
            executionService.complete(executionId, "a.txt, b.txt", false);
        } finally {
            TenantContext.clear();
        }

        ResponseEntity<AgentExecutionStatusResponse> getResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant.token())), AgentExecutionStatusResponse.class);

        assertThat(getResponse.getBody().status()).isEqualTo("SUCCEEDED");
        assertThat(getResponse.getBody().reply()).isEqualTo("a.txt, b.txt");
        assertThat(getResponse.getBody().toolWasUsed()).isFalse();
    }

    @Test
    void claimNext_underWorkerSentinel_seesJobRegardlessOfWhichTenantCreatedIt() {
        AuthResponse tenant = registerTenant("job-c");
        restTemplate.exchange(baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("hello", null), authHeaders(tenant.token())),
                AgentExecutionAccepted.class);

        TenantContext.set(TenantContext.SYSTEM_WORKER_TENANT_ID);
        try {
            assertThat(executionService.claimNext()).isPresent();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void claimNext_underAnOrdinaryTenantContext_cannotSeeAnyonesQueuedJobs() {
        AuthResponse tenantA = registerTenant("job-d");
        restTemplate.exchange(baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("hello", null), authHeaders(tenantA.token())),
                AgentExecutionAccepted.class);

        AuthResponse tenantB = registerTenant("job-e");
        // Even tenant B's own RLS-scoped connection must not see tenant A's
        // queued row via the claim query -- only the worker sentinel can.
        TenantContext.set(tenantB.tenantId());
        try {
            assertThat(executionService.claimNext()).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void getExecution_crossTenant_returns404NotAnotherTenantsData() {
        AuthResponse tenantA = registerTenant("job-f");
        ResponseEntity<AgentExecutionAccepted> postResponse = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("secret prompt", null), authHeaders(tenantA.token())),
                AgentExecutionAccepted.class);
        UUID executionId = postResponse.getBody().executionId();

        AuthResponse tenantB = registerTenant("job-g");
        ResponseEntity<String> getResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantB.token())), String.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getResponse.getBody()).doesNotContain("secret prompt");
    }

    @Test
    void execute_readOnlyRole_forbidden() {
        AuthResponse admin = registerTenant("job-h");
        AuthResponse readOnly = createReadOnlyAndLogin(admin);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("hello", null), authHeaders(readOnly.token())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private AuthResponse createReadOnlyAndLogin(AuthResponse admin) {
        String email = "readonly-" + UUID.randomUUID().toString().substring(0, 8) + "@" + admin.tenantSlug() + ".com";
        restTemplate.exchange(baseUrl() + "/users", HttpMethod.POST,
                new HttpEntity<>(new com.enterprisehub.dto.CreateUserRequest(email, "password123", "READONLY"),
                        authHeaders(admin.token())),
                Void.class);
        return restTemplate.postForEntity(baseUrl() + "/auth/login",
                new com.enterprisehub.dto.LoginRequest(admin.tenantSlug(), email, "password123"), AuthResponse.class).getBody();
    }
}
