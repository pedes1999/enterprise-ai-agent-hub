package com.enterprisehub.gateway.integration;

import com.enterprisehub.dto.AuthResponse;
import com.enterprisehub.dto.RegisterRequest;
import com.enterprisehub.gateway.entity.ToolExecution;
import com.enterprisehub.gateway.repository.ToolExecutionRepository;
import com.enterprisehub.gateway.tenant.TenantContext;
import com.enterprisehub.runtime.audit.ToolExecutionAuditRecord;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.audit.ToolExecutionOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves JpaToolExecutionListener + tool_executions' RLS policy actually
 * isolate tenants, without needing the sandbox sidecar or a real Anthropic
 * key -- calls the listener directly (as agent-runtime's
 * AbstractSandboxedTool would, synchronously on the request thread), with
 * TenantContext set exactly as TenantResolvingFilter would set it for a
 * real authenticated request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ToolExecutionAuditIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ToolExecutionListener toolExecutionListener;

    @Autowired
    private ToolExecutionRepository toolExecutionRepository;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private String registerTenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest request = new RegisterRequest(slug, slug, "admin@" + slug + ".com", "p@ssword123");
        AuthResponse response = restTemplate.postForEntity(
                "http://localhost:" + port + "/auth/register", request, AuthResponse.class).getBody();
        return response.tenantId();
    }

    @Test
    void onToolExecuted_persistsRow_readableByOwningTenant() {
        String tenantId = registerTenant("audit-a");

        TenantContext.set(tenantId);
        try {
            toolExecutionListener.onToolExecuted(new ToolExecutionAuditRecord(
                    tenantId, "exec-1", "run_shell_command",
                    Duration.ofMillis(123), ToolExecutionOutcome.SUCCESS, null));

            List<ToolExecution> rows = toolExecutionRepository.findByTenantId(UUID.fromString(tenantId));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getToolName()).isEqualTo("run_shell_command");
            assertThat(rows.get(0).getDurationMs()).isEqualTo(123);
            assertThat(rows.get(0).getOutcome()).isEqualTo("SUCCESS");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void crossTenant_cannotReadAnotherTenantsAuditRows() {
        String tenantA = registerTenant("audit-a");
        String tenantB = registerTenant("audit-b");

        TenantContext.set(tenantA);
        try {
            toolExecutionListener.onToolExecuted(new ToolExecutionAuditRecord(
                    tenantA, "exec-1", "run_shell_command", Duration.ofMillis(50), ToolExecutionOutcome.SUCCESS, null));
        } finally {
            TenantContext.clear();
        }

        // Even asking for tenant A's rows by explicit tenantId parameter,
        // while the DB session is scoped to tenant B, must return nothing --
        // RLS is what's actually enforcing this, not the query's WHERE
        // clause (which does correctly say "tenant A" here).
        TenantContext.set(tenantB);
        try {
            List<ToolExecution> rows = toolExecutionRepository.findByTenantId(UUID.fromString(tenantA));
            assertThat(rows).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void onToolExecuted_withNoTenantContextSet_insertFailsClosed() {
        // No TenantContext.set() at all -- simulates a bug where this got
        // called outside a real authenticated request. RLS's WITH CHECK
        // must reject the insert rather than silently accepting it with an
        // unscoped or wrong tenant.
        String tenantId = registerTenant("audit-noctx");

        assertThat(TenantContext.get()).isNull();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                toolExecutionListener.onToolExecuted(new ToolExecutionAuditRecord(
                        tenantId, "exec-1", "run_shell_command", Duration.ofMillis(1), ToolExecutionOutcome.SUCCESS, null)))
                .isInstanceOf(RuntimeException.class);
    }
}
