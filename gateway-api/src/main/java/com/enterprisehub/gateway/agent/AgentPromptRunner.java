package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.SharedExecutionContext;
import com.enterprisehub.core.SharedExecutionContextFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.agent.tools.CurrentDateTimeTool;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.credential.VendorCredentialService;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import com.enterprisehub.runtime.sandbox.SandboxSpec;
import com.enterprisehub.runtime.tools.GitCloneTool;
import com.enterprisehub.runtime.tools.ReadFileTool;
import com.enterprisehub.runtime.tools.RunShellCommandTool;
import com.enterprisehub.runtime.tools.WriteFileTool;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The actual "run this prompt, with tools, for this tenant" logic --
 * extracted out of AgentPingService so both the synchronous spike endpoint
 * (/agents/ping-with-tools) and AgentJobWorker's async path share exactly
 * one implementation of tool assembly and context building. Deliberately
 * has no notion of HTTP requests or agent_executions rows -- callers own
 * translating its result (or a thrown exception) into whichever response
 * shape they need.
 *
 * Every sandboxed tool for one run() call shares a single SandboxSession
 * (see its javadoc) instead of each getting its own throwaway sandbox --
 * this is what lets a multi-round sequence (clone, read a file, edit it,
 * run tests -- see ToolCallingChatEngine) actually see its own earlier
 * steps. Every credential kind a tool might need is resolved up front,
 * before the session's sandbox is created, because env vars can only be
 * injected at sandbox creation time -- see SandboxSession's javadoc for
 * why that ordering matters.
 */
@Service
public class AgentPromptRunner {

    private static final String GIT_CREDENTIAL_KIND = "GIT";
    private static final Duration SESSION_MAX_LIFETIME = Duration.ofMinutes(10);
    private static final long SESSION_MAX_OUTPUT_BYTES = 64 * 1024;

    private final VendorCredentialRepository vendorCredentialRepository;
    private final VendorCredentialService vendorCredentialService;
    private final SharedExecutionContextFactory sharedExecutionContextFactory;
    private final LlmProperties llmProperties;
    private final SandboxClient sandboxClient;
    private final ToolExecutionListener toolExecutionListener;
    private final CredentialResolver credentialResolver;

    public AgentPromptRunner(VendorCredentialRepository vendorCredentialRepository,
                              VendorCredentialService vendorCredentialService,
                              SharedExecutionContextFactory sharedExecutionContextFactory,
                              LlmProperties llmProperties,
                              SandboxClient sandboxClient,
                              ToolExecutionListener toolExecutionListener,
                              CredentialResolver credentialResolver) {
        this.vendorCredentialRepository = vendorCredentialRepository;
        this.vendorCredentialService = vendorCredentialService;
        this.sharedExecutionContextFactory = sharedExecutionContextFactory;
        this.llmProperties = llmProperties;
        this.sandboxClient = sandboxClient;
        this.toolExecutionListener = toolExecutionListener;
        this.credentialResolver = credentialResolver;
    }

    public String modelName() {
        return llmProperties.anthropicModelName();
    }

    /**
     * Resolves the tenant's Anthropic credential, assembles the standard
     * tool set, and runs the tool-calling loop to completion. Throws
     * AgentException/RuntimeException on failure (no active credential, or
     * the underlying LLM call itself failing) -- callers decide how to
     * surface that (a 4xx/502 HTTP response for the sync spike, a FAILED
     * agent_executions row for the async worker).
     */
    public ToolCallingChatEngine.ToolChatResult run(UUID tenantId, String executionId, String prompt) {
        String apiKey = resolveApiKey(tenantId);
        String modelName = llmProperties.anthropicModelName();

        SandboxSession session = new SandboxSession(sandboxClient, buildSessionSpec(tenantId, executionId));
        try {
            List<AgentTool> tools = List.of(
                    new CurrentDateTimeTool(),
                    new RunShellCommandTool(session, toolExecutionListener),
                    new GitCloneTool(session, toolExecutionListener, credentialResolver),
                    new ReadFileTool(session, toolExecutionListener),
                    new WriteFileTool(session, toolExecutionListener));
            SharedExecutionContext context = sharedExecutionContextFactory.create(
                    tenantId.toString(), executionId, LlmProvider.ANTHROPIC, apiKey, modelName, tools);

            return context.chat(prompt);
        } finally {
            // Runs exactly once per execution, regardless of how many tool
            // calls happened (or none at all -- endSession() no-ops if the
            // session's sandbox was never actually provisioned).
            session.endSession();
        }
    }

    /**
     * Every credential kind a sandboxed tool in this run might need,
     * resolved up front and merged into ONE spec -- see SandboxSession's
     * javadoc for why this can't happen lazily per-tool-call the way it
     * used to. Only GIT exists today; a future credential kind (e.g.
     * GITHUB for a "open a PR" tool) gets added here the same way.
     */
    private SandboxSpec buildSessionSpec(UUID tenantId, String executionId) {
        Map<String, String> credentials = new HashMap<>(credentialResolver.resolve(tenantId.toString(), GIT_CREDENTIAL_KIND));
        return new SandboxSpec(tenantId.toString(), executionId, credentials, SESSION_MAX_LIFETIME, SESSION_MAX_OUTPUT_BYTES);
    }

    private String resolveApiKey(UUID tenantId) {
        VendorCredential credential = vendorCredentialRepository.findByTenantIdAndProvider(tenantId, LlmProvider.ANTHROPIC.name())
                .filter(VendorCredential::isActive)
                .orElseThrow(() -> new AgentException(HttpStatus.BAD_REQUEST,
                        "No active ANTHROPIC credential configured for this tenant -- PUT /vendor-credentials first"));
        return vendorCredentialService.decryptToken(credential);
    }
}
