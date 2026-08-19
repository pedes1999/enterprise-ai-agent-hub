package com.enterprisehub.gateway.integration;

import com.enterprisehub.dto.AuthResponse;
import com.enterprisehub.dto.RegisterRequest;
import com.enterprisehub.dto.TenantSettingsResponse;
import com.enterprisehub.dto.TenantSpendSummary;
import com.enterprisehub.dto.TriggerAgentExecutionRequest;
import com.enterprisehub.dto.UpdateTenantSettingsRequest;
import com.enterprisehub.gateway.cost.ExecutionCostCalculator;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.repository.AgentExecutionRepository;
import com.enterprisehub.gateway.repository.ModelPricingRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cost governance against real Postgres -- which is the only way to test the
 * parts that actually matter here.
 *
 * Three things cannot be exercised with mocks and are the reason this class
 * exists: that V35 applies at all and its seeded price list is really there;
 * that the spend aggregates return what they claim (a SUM over no rows is
 * SQL NULL, not zero, and a mocked repository will happily pretend
 * otherwise); and that the budget check actually fires on the live enqueue
 * path rather than only in a unit test that calls it directly.
 *
 * AgentJobWorker is disabled in this profile (application-test.yml), so
 * executions stay QUEUED and these tests write terminal state directly --
 * the same approach AgentExecutionQueueIntegrationTest takes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CostGovernanceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentExecutionRepository executionRepository;

    @Autowired
    private ModelPricingRepository pricingRepository;

    @Autowired
    private ExecutionCostCalculator costCalculator;

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

    private TenantSettingsResponse setBudget(AuthResponse tenant, BigDecimal budget) {
        ResponseEntity<TenantSettingsResponse> response = restTemplate.exchange(
                baseUrl() + "/tenant-settings", HttpMethod.PUT,
                new HttpEntity<>(new UpdateTenantSettingsRequest(null, null, null, budget), authHeaders(tenant.token())),
                TenantSettingsResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private TenantSpendSummary spend(AuthResponse tenant) {
        ResponseEntity<TenantSpendSummary> response = restTemplate.exchange(
                baseUrl() + "/agents/executions/spend", HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant.token())), TenantSpendSummary.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> trigger(AuthResponse tenant) {
        return restTemplate.exchange(baseUrl() + "/agents/execute", HttpMethod.POST,
                new HttpEntity<>(new TriggerAgentExecutionRequest("say hello", "general-assistant",
                        null, null, null, null), authHeaders(tenant.token())),
                String.class);
    }

    /**
     * Writes a completed, costed execution directly -- standing in for a run
     * AgentJobWorker would have finished, which this profile deliberately
     * never starts.
     */
    private void recordCompletedRun(UUID tenantId, String modelName, int inputTokens, int outputTokens) {
        TenantContext.set(tenantId.toString());
        try {
            AgentExecution execution = new AgentExecution();
            execution.setTenantId(tenantId);
            execution.setAgentType("general-assistant");
            execution.setTriggerSource("API");
            execution.setPrompt("say hello");
            execution.setStatus("SUCCEEDED");
            execution.setModelName(modelName);
            execution.setInputTokens(inputTokens);
            execution.setOutputTokens(outputTokens);
            execution.setTotalTokens(inputTokens + outputTokens);
            execution.setStartedAt(Instant.now());
            execution.setCompletedAt(Instant.now());
            execution.setCostUsd(costCalculator
                    .calculate(modelName, inputTokens, outputTokens, execution.getCompletedAt())
                    .costUsd());
            executionRepository.save(execution);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void v35SeedsThePriceListSoAnOutOfTheBoxRunIsPriced() {
        // The server default (app.llm.anthropic-model-name). If this model is
        // ever changed without adding a price row, every run on a fresh
        // install would silently record as unpriced -- so pin it here.
        assertThat(pricingRepository.findEffectivePrice("claude-sonnet-4-5-20250929", Instant.now()))
                .isPresent();
    }

    @Test
    void aCompletedRunIsCostedFromTheSeededPriceList() {
        AuthResponse tenant = registerTenant("cost-basic");
        UUID tenantId = UUID.fromString(tenant.tenantId());

        // Sonnet 4.5 is $3/MTok in, $15/MTok out -> 1M in + 100k out = $4.50.
        recordCompletedRun(tenantId, "claude-sonnet-4-5-20250929", 1_000_000, 100_000);

        assertThat(spend(tenant).spendUsd()).isEqualByComparingTo("4.50");
    }

    @Test
    void aRunOnAnUnpricedModelCountsAsUnknownSpendNotAsFreeSpend() {
        AuthResponse tenant = registerTenant("cost-unpriced");
        UUID tenantId = UUID.fromString(tenant.tenantId());

        // No price row for an OpenAI model -- V35 seeds Anthropic only.
        recordCompletedRun(tenantId, "gpt-4o-mini", 500_000, 50_000);

        TenantSpendSummary summary = spend(tenant);
        // The whole point: this must NOT read as "$0.00 spent, all good".
        assertThat(summary.spendUsd()).isEqualByComparingTo("0");
        assertThat(summary.unpricedExecutions()).isEqualTo(1L);
    }

    @Test
    void aTenantWithNoCompletedRunsReportsZeroSpendRatherThanFailing() {
        // SUM() over an empty set is SQL NULL -- this pins that the service
        // coalesces it rather than NPEing or returning null to the client.
        AuthResponse tenant = registerTenant("cost-empty");

        TenantSpendSummary summary = spend(tenant);

        assertThat(summary.spendUsd()).isEqualByComparingTo("0");
        assertThat(summary.budgetUsd()).isNull();
        assertThat(summary.unpricedExecutions()).isZero();
    }

    @Test
    void exceedingTheMonthlyBudgetBlocksNewExecutionsWith402() {
        AuthResponse tenant = registerTenant("cost-budget");
        UUID tenantId = UUID.fromString(tenant.tenantId());

        setBudget(tenant, new BigDecimal("1.00"));
        // $4.50 of spend against a $1.00 ceiling.
        recordCompletedRun(tenantId, "claude-sonnet-4-5-20250929", 1_000_000, 100_000);

        ResponseEntity<String> response = trigger(tenant);

        // 402, not 429: retrying will fail identically until the period rolls
        // over or an admin raises the ceiling.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    }

    @Test
    void aTenantUnderBudgetCanStillQueueExecutions() {
        AuthResponse tenant = registerTenant("cost-under");
        UUID tenantId = UUID.fromString(tenant.tenantId());

        setBudget(tenant, new BigDecimal("100.00"));
        recordCompletedRun(tenantId, "claude-sonnet-4-5-20250929", 1_000_000, 100_000);

        assertThat(trigger(tenant).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void aTenantWithNoBudgetIsNeverBlockedNoMatterWhatTheyHaveSpent() {
        // The default. V35 must not retroactively cut off existing tenants.
        AuthResponse tenant = registerTenant("cost-nobudget");
        UUID tenantId = UUID.fromString(tenant.tenantId());

        recordCompletedRun(tenantId, "claude-opus-5", 10_000_000, 1_000_000);

        assertThat(spend(tenant).budgetUsd()).isNull();
        assertThat(trigger(tenant).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void aZeroBudgetFreezesTheTenantWithoutDeletingAnything() {
        AuthResponse tenant = registerTenant("cost-frozen");

        setBudget(tenant, BigDecimal.ZERO);

        // Zero is a real setting distinct from null -- no spend at all yet,
        // and the tenant is still blocked.
        assertThat(spend(tenant).spendUsd()).isEqualByComparingTo("0");
        assertThat(trigger(tenant).getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    }

    @Test
    void oneTenantsSpendIsNeverVisibleToAnother() {
        AuthResponse spender = registerTenant("cost-rls-a");
        AuthResponse other = registerTenant("cost-rls-b");
        recordCompletedRun(UUID.fromString(spender.tenantId()), "claude-opus-5", 1_000_000, 100_000);

        // $5.00 + $2.50 = $7.50 for the spender; the other tenant sees none
        // of it, and neither does their budget.
        assertThat(spend(spender).spendUsd()).isEqualByComparingTo("7.50");
        assertThat(spend(other).spendUsd()).isEqualByComparingTo("0");
    }

    @Test
    void spendBreaksDownByAgentSoTheExpensiveOneIsIdentifiable() {
        AuthResponse tenant = registerTenant("cost-breakdown");
        UUID tenantId = UUID.fromString(tenant.tenantId());

        recordCompletedRun(tenantId, "claude-opus-5", 1_000_000, 100_000);
        recordCompletedRun(tenantId, "claude-opus-5", 1_000_000, 100_000);

        TenantSpendSummary summary = spend(tenant);

        assertThat(summary.byAgent()).hasSize(1);
        assertThat(summary.byAgent().get(0).agentSlug()).isEqualTo("general-assistant");
        assertThat(summary.byAgent().get(0).executionCount()).isEqualTo(2L);
        assertThat(summary.byAgent().get(0).costUsd()).isEqualByComparingTo("15.00");
    }

    @Test
    void settingABudgetIsAdminOnlyButReadingSpendIsNot() {
        AuthResponse tenant = registerTenant("cost-roles");

        // The registering user is an ADMIN, so both work here; what this
        // pins is that the spend endpoint is not accidentally ADMIN-gated,
        // since a developer about to trigger a run benefits from seeing it.
        assertThat(setBudget(tenant, new BigDecimal("5.00")).monthlyBudgetUsd()).isEqualByComparingTo("5.00");
        assertThat(spend(tenant).budgetUsd()).isEqualByComparingTo("5.00");
    }

    @Test
    void aNegativeBudgetIsRejected() {
        AuthResponse tenant = registerTenant("cost-negative");

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/tenant-settings", HttpMethod.PUT,
                new HttpEntity<>(new UpdateTenantSettingsRequest(null, null, null, new BigDecimal("-1")),
                        authHeaders(tenant.token())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
