package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.http.SandboxClientHttpImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Named *IT (not *Test) so Maven Surefire's default include pattern skips
 * it automatically in every normal `mvn test` run -- this makes a REAL call
 * through a REAL running sidecar to a REAL E2B sandbox (real infrastructure
 * spun up, real cost), same discipline as agent-core's
 * LlmEngineFactoryManualIT. @EnabledIfEnvironmentVariable is a second,
 * independent guard: self-skips (not fails) if SANDBOX_SIDECAR_URL isn't
 * set, so cloning this repo elsewhere without a running sidecar is
 * harmless.
 *
 * Requires, separately from this test:
 *   1. agent-runtime/sidecar running locally (`npm start`, with a real
 *      E2B_API_KEY set) -- see that directory's README.
 *   2. SANDBOX_SIDECAR_URL pointing at it, e.g.:
 *      SANDBOX_SIDECAR_URL=http://localhost:8090/ mvn test -pl agent-runtime -Dtest=RunShellCommandToolManualIT
 *
 * Verified passing against a real E2B account, both running the sidecar
 * directly (`node --env-file=.env server.js`) and containerized via its
 * Dockerfile -- see the sidecar's own README for both confirmed runs.
 */
@EnabledIfEnvironmentVariable(named = "SANDBOX_SIDECAR_URL", matches = ".+")
class RunShellCommandToolManualIT {

    @Test
    void realSandbox_runsCommand_andReturnsRealOutput() {
        URI sidecarUri = URI.create(System.getenv("SANDBOX_SIDECAR_URL"));
        SandboxClient sandboxClient = new SandboxClientHttpImpl(sidecarUri);
        ToolExecutionListener noopListener = record -> System.out.println("Audit: " + record);

        RunShellCommandTool tool = new RunShellCommandTool(sandboxClient, noopListener);
        ToolExecutionContext context = new ToolExecutionContext("manual-it-tenant", UUID.randomUUID().toString());

        String result = tool.execute(context, Map.of("command", "echo hello from a real sandbox"));

        System.out.println("Real sandbox result:\n" + result);
        assertThat(result).contains("exit_code: 0").contains("hello from a real sandbox");
    }

    @Test
    void realSandbox_isEphemeral_freshSandboxPerExecution() {
        URI sidecarUri = URI.create(System.getenv("SANDBOX_SIDECAR_URL"));
        SandboxClient sandboxClient = new SandboxClientHttpImpl(sidecarUri);
        ToolExecutionListener noopListener = record -> {
        };
        RunShellCommandTool tool = new RunShellCommandTool(sandboxClient, noopListener);

        // Two separate executions must not share state -- a file written in
        // one sandbox must not be visible in a fresh one.
        ToolExecutionContext contextA = new ToolExecutionContext("manual-it-tenant", UUID.randomUUID().toString());
        tool.execute(contextA, Map.of("command", "echo marker > /tmp/marker.txt"));

        ToolExecutionContext contextB = new ToolExecutionContext("manual-it-tenant", UUID.randomUUID().toString());
        String result = tool.execute(contextB, Map.of("command", "cat /tmp/marker.txt || echo NOT_FOUND"));

        assertThat(result).contains("NOT_FOUND");
    }
}
