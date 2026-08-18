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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.enterprisehub.gateway.agent.EnqueueExecutionCommand;

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
        RegisterRequest request = new RegisterRequest(slug, slug, "admin@" + slug + ".com", "p@ssword123");
        return restTemplate.postForEntity(baseUrl() + "/auth/register", request, AuthResponse.class).getBody();
    }

    /**
     * The lockout fixed by V32__agent_execution_heartbeat.sql, end to end
     * against real Postgres: a job claimed by an instance that then dies
     * stays RUNNING forever, and because RUNNING counts toward the
     * per-tenant concurrency cap, those rows permanently consume the
     * tenant's capacity until it can never trigger anything again.
     *
     * Simulating the crash is exactly "claim the row and then never touch
     * it again", which is what the claim-without-complete below does -- no
     * heartbeat is ever stamped for it, which is precisely how a real dead
     * instance looks to every surviving one.
     */
    @Test
    void abandonedRunningExecutions_areReapedAndStopConsumingTheConcurrencyCap() {
        AuthResponse tenant = registerTenant("reap");
        UUID tenantId = UUID.fromString(tenant.tenantId());

        // Fill the tenant's entire concurrency allowance with jobs, then
        // claim each one and walk away -- the crash-mid-run state.
        int cap = executionService.getUsage(tenantId).limit();
        List<UUID> abandonedIds = new ArrayList<>();
        TenantContext.set(tenantId.toString());
        try {
            for (int i = 0; i < cap; i++) {
                abandonedIds.add(executionService.enqueue(EnqueueExecutionCommand.forAgent(tenantId, "general-assistant")
                        .prompt("abandoned job " + i)
                        .build()).getId());
            }
        } finally {
            TenantContext.clear();
        }
        // Claim each of THIS tenant's jobs specifically. claimNext() takes the
        // oldest QUEUED row across every tenant (that's the worker-sentinel
        // carve-out working as designed), so on a shared test database a plain
        // "claim cap times" would mostly claim other tests' rows and leave
        // this tenant's still QUEUED -- which counts toward the cap too, and
        // would make the reap below look like it had failed.
        abandonedIds.forEach(this::drainUntilClaimed);

        // The tenant is now locked out: every slot is held by a RUNNING row
        // that nothing will ever finish.
        TenantContext.set(tenantId.toString());
        try {
            assertThat(executionService.getUsage(tenantId).active()).isEqualTo(cap);
            assertThatThrownBy(() -> executionService.enqueue(
                    EnqueueExecutionCommand.forAgent(tenantId, "general-assistant").prompt("blocked").build()))
                    .hasMessageContaining("executions in progress");
        } finally {
            TenantContext.clear();
        }

        // A sweep with a zero staleness window treats anything not stamped
        // *right now* as abandoned -- the same code path the scheduled
        // reaper runs, just without waiting out the real 5-minute window.
        TenantContext.set(TenantContext.SYSTEM_WORKER_TENANT_ID);
        int reaped;
        try {
            reaped = executionService.reapStaleRunning(Duration.ZERO);
        } finally {
            TenantContext.clear();
        }
        assertThat(reaped).isGreaterThanOrEqualTo(cap);

        // Capacity is back, and the reaped rows explain themselves rather
        // than just vanishing.
        TenantContext.set(tenantId.toString());
        try {
            assertThat(executionService.getUsage(tenantId).active()).isZero();
            AgentExecution recovered = executionService.enqueue(
                    EnqueueExecutionCommand.forAgent(tenantId, "general-assistant").prompt("now allowed").build());
            assertThat(recovered.getStatus()).isEqualTo("QUEUED");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A job the owning instance is still stamping must survive a sweep --
     * the failure mode that would make this whole mechanism worse than the
     * bug it fixes, by killing live, paid-for agent runs.
     */
    @Test
    void heartbeatedRunningExecution_survivesTheReaper() {
        AuthResponse tenant = registerTenant("beat");
        UUID tenantId = UUID.fromString(tenant.tenantId());

        UUID executionId;
        TenantContext.set(tenantId.toString());
        try {
            executionId = executionService.enqueue(EnqueueExecutionCommand.forAgent(tenantId, "general-assistant")
                    .prompt("a job that is genuinely still running")
                    .build()).getId();
        } finally {
            TenantContext.clear();
        }

        AgentExecution claimed = drainUntilClaimed(executionId);
        assertThat(claimed.getStatus()).isEqualTo("RUNNING");

        TenantContext.set(TenantContext.SYSTEM_WORKER_TENANT_ID);
        try {
            // What ExecutionHeartbeatMonitor does on every tick for the jobs
            // its instance owns.
            executionService.heartbeat(List.of(executionId));
            // A one-minute window: the beat above is seconds old, so this row
            // is comfortably inside it and must be left alone.
            executionService.reapStaleRunning(Duration.ofMinutes(1));
        } finally {
            TenantContext.clear();
        }

        TenantContext.set(tenantId.toString());
        try {
            assertThat(executionService.findForTenant(tenantId, executionId))
                    .get()
                    .satisfies(execution -> assertThat(execution.getStatus()).isEqualTo("RUNNING"));
        } finally {
            TenantContext.clear();
        }
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
                new HttpEntity<>(new TriggerAgentExecutionRequest("list files", null, null, null, null, null), authHeaders(tenant.token())),
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
                new HttpEntity<>(new TriggerAgentExecutionRequest("list files", null, null, null, null, null), authHeaders(tenant.token())),
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
                new HttpEntity<>(new TriggerAgentExecutionRequest("hello", null, null, null, null, null), authHeaders(tenant.token())),
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
                new HttpEntity<>(new TriggerAgentExecutionRequest("hello", null, null, null, null, null), authHeaders(tenantA.token())),
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
                new HttpEntity<>(new TriggerAgentExecutionRequest("secret prompt", null, null, null, null, null), authHeaders(tenantA.token())),
                AgentExecutionAccepted.class);
        UUID executionId = postResponse.getBody().executionId();

        AuthResponse tenantB = registerTenant("job-g");
        ResponseEntity<String> getResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantB.token())), String.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getResponse.getBody()).doesNotContain("secret prompt");
    }

    /**
     * parentExecutionId isn't settable via the public /agents/execute API
     * (it only exists for delegate_to_agent-created rows -- see
     * V25__agent_execution_parent_and_planner.sql) -- exercised here by
     * calling enqueue() directly with parentExecutionId set, the same way
     * DelegateToAgentTool does, then verified through both the child's own
     * GET /agents/executions/{id} and the parent's GET
     * .../{id}/children.
     */
    // ---------- cancellation ----------

    @Test
    void cancel_queuedExecution_flipsToCancelledAndFreesTheConcurrencyCapSlot() {
        AuthResponse tenant = registerTenant("cancel-a");
        UUID tenantId = UUID.fromString(tenant.tenantId());
        ResponseEntity<AgentExecutionAccepted> postResponse = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("list files", null, null, null, null, null), authHeaders(tenant.token())),
                AgentExecutionAccepted.class);
        UUID executionId = postResponse.getBody().executionId();

        ResponseEntity<Void> cancelResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant.token())), Void.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<AgentExecutionStatusResponse> getResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant.token())), AgentExecutionStatusResponse.class);
        assertThat(getResponse.getBody().status()).isEqualTo("CANCELLED");

        TenantContext.set(tenantId.toString());
        try {
            // A QUEUED cancel was never claimed -- it must stop counting
            // against the concurrency cap same as any other terminal row.
            assertThat(executionService.getUsage(tenantId).active()).isZero();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cancel_runningExecution_setsTheFlagButLeavesStatusRunning() {
        // Cooperative, not instant: the row this endpoint touches is
        // claimed (RUNNING), so it can only flag the pending cancellation --
        // the actual CANCELLED transition is AgentJobWorker's job once its
        // loop notices, which this endpoint alone can't simulate without a
        // real running job. cancellationRequestedAt is the visible proof the
        // flag was actually set.
        AuthResponse tenant = registerTenant("cancel-b");
        ResponseEntity<AgentExecutionAccepted> postResponse = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("list files", null, null, null, null, null), authHeaders(tenant.token())),
                AgentExecutionAccepted.class);
        UUID executionId = postResponse.getBody().executionId();
        AgentExecution claimed = drainUntilClaimed(executionId);
        assertThat(claimed.getStatus()).isEqualTo("RUNNING");

        ResponseEntity<Void> cancelResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant.token())), Void.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<AgentExecutionStatusResponse> getResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant.token())), AgentExecutionStatusResponse.class);
        assertThat(getResponse.getBody().status()).isEqualTo("RUNNING");
        assertThat(getResponse.getBody().cancellationRequestedAt()).isNotNull();
    }

    @Test
    void cancel_alreadyTerminalExecution_returns409Conflict() {
        AuthResponse tenant = registerTenant("cancel-c");
        ResponseEntity<AgentExecutionAccepted> postResponse = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("list files", null, null, null, null, null), authHeaders(tenant.token())),
                AgentExecutionAccepted.class);
        UUID executionId = postResponse.getBody().executionId();
        AgentExecution claimed = drainUntilClaimed(executionId);
        TenantContext.set(tenant.tenantId());
        try {
            executionService.complete(claimed.getId(), "done", false);
        } finally {
            TenantContext.clear();
        }

        ResponseEntity<String> cancelResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant.token())), String.class);

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancel_crossTenant_returns404NotAnotherTenantsData() {
        AuthResponse tenantA = registerTenant("cancel-d");
        ResponseEntity<AgentExecutionAccepted> postResponse = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("secret prompt", null, null, null, null, null), authHeaders(tenantA.token())),
                AgentExecutionAccepted.class);
        UUID executionId = postResponse.getBody().executionId();

        AuthResponse tenantB = registerTenant("cancel-e");
        ResponseEntity<String> cancelResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenantB.token())), String.class);

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Confirm tenant A's execution was genuinely untouched, not just that
        // the response was a 404 -- still QUEUED, not CANCELLED.
        ResponseEntity<AgentExecutionStatusResponse> getResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantA.token())), AgentExecutionStatusResponse.class);
        assertThat(getResponse.getBody().status()).isEqualTo("QUEUED");
    }

    @Test
    void cancel_readOnlyRole_forbidden() {
        AuthResponse admin = registerTenant("cancel-f");
        ResponseEntity<AgentExecutionAccepted> postResponse = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("list files", null, null, null, null, null), authHeaders(admin.token())),
                AgentExecutionAccepted.class);
        UUID executionId = postResponse.getBody().executionId();
        AuthResponse readOnly = createReadOnlyAndLogin(admin);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + executionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(authHeaders(readOnly.token())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void enqueueWithParentExecutionId_roundTripsThroughGetAndChildrenEndpoints() {
        AuthResponse tenant = registerTenant("job-i");
        UUID tenantId = UUID.fromString(tenant.tenantId());

        TenantContext.set(tenant.tenantId());
        AgentExecution parent;
        AgentExecution child;
        try {
            parent = executionService.enqueue(EnqueueExecutionCommand.forAgent(tenantId, "general-assistant")
                    .prompt("parent prompt")
                    .build());
            child = executionService.enqueue(EnqueueExecutionCommand.forAgent(tenantId, "general-assistant")
                    .prompt("child prompt")
                    .parentExecutionId(parent.getId())
                    .build());
        } finally {
            TenantContext.clear();
        }

        ResponseEntity<AgentExecutionStatusResponse> childResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + child.getId(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant.token())), AgentExecutionStatusResponse.class);
        assertThat(childResponse.getBody().parentExecutionId()).isEqualTo(parent.getId());

        ResponseEntity<AgentExecutionStatusResponse[]> childrenResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + parent.getId() + "/children", HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant.token())), AgentExecutionStatusResponse[].class);
        assertThat(childrenResponse.getBody()).extracting(AgentExecutionStatusResponse::id).containsExactly(child.getId());

        ResponseEntity<AgentExecutionStatusResponse> parentResponse = restTemplate.exchange(
                baseUrl() + "/agents/executions/" + parent.getId(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant.token())), AgentExecutionStatusResponse.class);
        assertThat(parentResponse.getBody().parentExecutionId()).isNull();
    }

    @Test
    void execute_readOnlyRole_forbidden() {
        AuthResponse admin = registerTenant("job-h");
        AuthResponse readOnly = createReadOnlyAndLogin(admin);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("hello", null, null, null, null, null), authHeaders(readOnly.token())),
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
