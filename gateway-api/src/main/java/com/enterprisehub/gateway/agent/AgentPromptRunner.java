package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.SharedExecutionContext;
import com.enterprisehub.core.SharedExecutionContextFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.agent.catalog.ToolCatalog;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.credential.VendorCredentialService;
import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import com.enterprisehub.runtime.sandbox.SandboxSpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The actual "run this NAMED agent's prompt, with its own tools, for this
 * tenant" logic -- extracted out of AgentPingService so both the
 * synchronous spike endpoint (/agents/ping-with-tools) and AgentJobWorker's
 * async path share exactly one implementation of tool assembly and context
 * building. Deliberately has no notion of HTTP requests or
 * agent_executions rows -- callers own translating its result (or a thrown
 * exception) into whichever response shape they need.
 *
 * "Named agent" means an AgentDefinition row (see V6__agent_definitions.sql) --
 * a persona (system prompt) plus a curated tool_names list, resolved via
 * ToolCatalog. This is the actual mechanism behind "a library of hundreds
 * of agents and tools": adding agent #101 means one new AgentDefinition
 * row, not a code change here.
 *
 * Every sandboxed tool for one run() call shares a single SandboxSession
 * (see its javadoc) instead of each getting its own throwaway sandbox --
 * this is what lets a multi-round sequence (clone, read a file, edit it,
 * run tests -- see ToolCallingChatEngine) actually see its own earlier
 * steps. Every credential kind a tool actually IN this definition's
 * tool_names might need is resolved up front, before the session's sandbox
 * is created, because env vars can only be injected at sandbox creation
 * time -- see SandboxSession's javadoc for why that ordering matters.
 */
@Service
public class AgentPromptRunner {

    /** The agent used when a caller doesn't request one by name -- mirrors the original ping-with-tools spike's tool set. */
    public static final String DEFAULT_AGENT_SLUG = "general-assistant";

    private static final String GIT_CREDENTIAL_KIND = "GIT";
    private static final String GIT_CLONE_TOOL_NAME = "git_clone";
    private static final String GITHUB_CREDENTIAL_KIND = "GITHUB";
    private static final String OPEN_PULL_REQUEST_TOOL_NAME = "open_pull_request";
    private static final Duration SESSION_MAX_LIFETIME = Duration.ofMinutes(10);
    private static final long SESSION_MAX_OUTPUT_BYTES = 64 * 1024;

    private final VendorCredentialRepository vendorCredentialRepository;
    private final VendorCredentialService vendorCredentialService;
    private final SharedExecutionContextFactory sharedExecutionContextFactory;
    private final LlmProperties llmProperties;
    private final SandboxClient sandboxClient;
    private final ToolExecutionListener toolExecutionListener;
    private final CredentialResolver credentialResolver;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final ToolCatalog toolCatalog;

    public AgentPromptRunner(VendorCredentialRepository vendorCredentialRepository,
                              VendorCredentialService vendorCredentialService,
                              SharedExecutionContextFactory sharedExecutionContextFactory,
                              LlmProperties llmProperties,
                              SandboxClient sandboxClient,
                              ToolExecutionListener toolExecutionListener,
                              CredentialResolver credentialResolver,
                              AgentDefinitionRepository agentDefinitionRepository,
                              ToolCatalog toolCatalog) {
        this.vendorCredentialRepository = vendorCredentialRepository;
        this.vendorCredentialService = vendorCredentialService;
        this.sharedExecutionContextFactory = sharedExecutionContextFactory;
        this.llmProperties = llmProperties;
        this.sandboxClient = sandboxClient;
        this.toolExecutionListener = toolExecutionListener;
        this.credentialResolver = credentialResolver;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.toolCatalog = toolCatalog;
    }

    public String modelName() {
        return llmProperties.anthropicModelName();
    }

    /**
     * Resolves the named agent definition, the tenant's Anthropic
     * credential, assembles that definition's own tool set, and runs the
     * tool-calling loop to completion. Throws AgentException/RuntimeException
     * on failure (unknown agent, no active credential, or the underlying
     * LLM call itself failing) -- callers decide how to surface that (a
     * 4xx/502 HTTP response for the sync spike, a FAILED agent_executions
     * row for the async worker).
     */
    public ToolCallingChatEngine.ToolChatResult run(UUID tenantId, String executionId, String agentSlug, String prompt) {
        AgentDefinition definition = resolveAgentDefinition(agentSlug);
        String apiKey = resolveApiKey(tenantId);
        String modelName = llmProperties.anthropicModelName();

        SandboxSession session = new SandboxSession(sandboxClient, buildSessionSpec(tenantId, executionId, definition));
        try {
            List<AgentTool> tools = toolCatalog.instantiate(definition.getToolNames(), session, toolExecutionListener, credentialResolver);
            SharedExecutionContext context = sharedExecutionContextFactory.create(
                    tenantId.toString(), executionId, LlmProvider.ANTHROPIC, apiKey, modelName, tools, definition.getSystemPrompt());

            return context.chat(prompt);
        } finally {
            // Runs exactly once per execution, regardless of how many tool
            // calls happened (or none at all -- endSession() no-ops if the
            // session's sandbox was never actually provisioned).
            session.endSession();
        }
    }

    private AgentDefinition resolveAgentDefinition(String agentSlug) {
        return agentDefinitionRepository.findBySlugAndActiveTrue(agentSlug)
                .orElseThrow(() -> new AgentException(HttpStatus.BAD_REQUEST, "Unknown or inactive agent: " + agentSlug));
    }

    /**
     * Every credential kind a tool ACTUALLY IN this definition's tool set
     * might need, resolved up front and merged into ONE spec -- see
     * SandboxSession's javadoc for why this can't happen lazily
     * per-tool-call the way it used to. A new credential kind for a new
     * tool gets added here the same way: gated on that tool's name being
     * present in the definition, never resolved unconditionally.
     */
    private SandboxSpec buildSessionSpec(UUID tenantId, String executionId, AgentDefinition definition) {
        Map<String, String> credentials = new HashMap<>();
        if (definition.getToolNames().contains(GIT_CLONE_TOOL_NAME)) {
            credentials.putAll(credentialResolver.resolve(tenantId.toString(), GIT_CREDENTIAL_KIND));
        }
        if (definition.getToolNames().contains(OPEN_PULL_REQUEST_TOOL_NAME)) {
            credentials.putAll(credentialResolver.resolve(tenantId.toString(), GITHUB_CREDENTIAL_KIND));
        }
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
