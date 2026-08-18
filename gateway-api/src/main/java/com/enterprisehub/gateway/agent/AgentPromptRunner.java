package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.SharedExecutionContext;
import com.enterprisehub.core.SharedExecutionContextFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ChatEngineOptions;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.gateway.agent.catalog.ToolCatalog;
import com.enterprisehub.gateway.agent.catalog.ToolCreationContext;
import com.enterprisehub.gateway.agent.input.InputSourceResolverRegistry;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.credential.VendorCredentialService;
import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.gateway.tenant.TenantLlmProviderResolver;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import com.enterprisehub.runtime.sandbox.SandboxSpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
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
    private final TenantLlmProviderResolver tenantLlmProviderResolver;
    private final SandboxClient sandboxClient;
    private final ToolExecutionListener toolExecutionListener;
    private final CredentialResolver credentialResolver;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final ToolCatalog toolCatalog;
    private final InputSourceResolverRegistry inputSourceResolverRegistry;

    public AgentPromptRunner(VendorCredentialRepository vendorCredentialRepository,
                              VendorCredentialService vendorCredentialService,
                              SharedExecutionContextFactory sharedExecutionContextFactory,
                              LlmProperties llmProperties,
                              TenantLlmProviderResolver tenantLlmProviderResolver,
                              SandboxClient sandboxClient,
                              ToolExecutionListener toolExecutionListener,
                              CredentialResolver credentialResolver,
                              AgentDefinitionRepository agentDefinitionRepository,
                              ToolCatalog toolCatalog,
                              InputSourceResolverRegistry inputSourceResolverRegistry) {
        this.vendorCredentialRepository = vendorCredentialRepository;
        this.vendorCredentialService = vendorCredentialService;
        this.sharedExecutionContextFactory = sharedExecutionContextFactory;
        this.llmProperties = llmProperties;
        this.tenantLlmProviderResolver = tenantLlmProviderResolver;
        this.sandboxClient = sandboxClient;
        this.toolExecutionListener = toolExecutionListener;
        this.credentialResolver = credentialResolver;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.toolCatalog = toolCatalog;
        this.inputSourceResolverRegistry = inputSourceResolverRegistry;
    }

    /** The model name depends on which provider the tenant resolves to -- see TenantLlmProviderResolver. */
    public String modelName(UUID tenantId) {
        return tenantLlmProviderResolver.resolveModelName(tenantId, tenantLlmProviderResolver.resolve(tenantId));
    }

    /**
     * Resolves the named agent definition, the tenant's Anthropic
     * credential, assembles that definition's own tool set, and runs the
     * tool-calling loop to completion. Throws AgentException/RuntimeException
     * on failure (unknown agent, no active credential, or the underlying
     * LLM call itself failing) -- callers decide how to surface that (a
     * 4xx/502 HTTP response for the sync spike, a FAILED agent_executions
     * row for the async worker).
     *
     * An AgentDefinition with no inputSourceType (general-assistant,
     * ticket-resolver today) ignores the request's repositoryUrl/
     * repositoryBranch/inputParameters entirely: assemblePrompt() reduces to
     * exactly the request's own prompt. See AgentRunRequest for the
     * per-field contracts.
     */
    public ToolCallingChatEngine.ToolChatResult run(AgentRunRequest request) {
        UUID tenantId = request.tenantId();
        UUID userId = request.userId();
        String executionId = request.executionId();
        Integer maxTokensOverride = request.maxTokensOverride();

        AgentDefinition definition = resolveAgentDefinition(request.agentSlug());
        LlmProvider provider = tenantLlmProviderResolver.resolve(tenantId);
        String apiKey = resolveApiKey(tenantId, userId, provider);
        // The definition's own preferred model (if set) overrides the
        // tenant's resolved model name -- provider (and therefore which
        // credential apiKey above resolved) is untouched either way, only
        // which model that same credential talks to. See
        // AgentDefinition.preferredModelName's javadoc.
        String modelName = definition.getPreferredModelName() != null
                ? definition.getPreferredModelName()
                : tenantLlmProviderResolver.resolveModelName(tenantId, provider);
        Integer maxTokens = maxTokensOverride != null ? maxTokensOverride : tenantLlmProviderResolver.resolveMaxTokens(tenantId);
        String resolvedInput = resolveInput(definition, tenantId, request.inputParameters());
        String assembledPrompt = assemblePrompt(request.repositoryUrl(), request.repositoryBranch(), resolvedInput, request.prompt());

        SandboxSession session = new SandboxSession(sandboxClient, buildSessionSpec(tenantId, executionId, definition));
        try {
            ToolCreationContext toolContext = new ToolCreationContext(tenantId.toString(), userId == null ? null : userId.toString(), definition.getId());
            List<AgentTool> tools = toolCatalog.instantiate(definition.getToolNames(), session, toolExecutionListener, credentialResolver, toolContext);
            SharedExecutionContext context = sharedExecutionContextFactory.create(
                    tenantId.toString(), executionId, provider, apiKey, modelName, tools,
                    llmProperties.baseUrl(provider),
                    ChatEngineOptions.builder()
                            .systemPrompt(definition.getSystemPrompt())
                            .maxTokensBudget(maxTokens)
                            .maxToolRounds(llmProperties.maxToolRounds())
                            .build());

            return context.chat(assembledPrompt);
        } finally {
            // Runs exactly once per execution, regardless of how many tool
            // calls happened (or none at all -- endSession() no-ops if the
            // session's sandbox was never actually provisioned).
            session.endSession();
        }
    }

    /** Empty (not null) when this definition has no inputSourceType configured -- assemblePrompt() then drops this section entirely. */
    private String resolveInput(AgentDefinition definition, UUID tenantId, Map<String, String> inputParameters) {
        String inputSourceType = definition.getInputSourceType();
        if (inputSourceType == null || inputSourceType.isBlank()) {
            return "";
        }
        return inputSourceResolverRegistry.resolve(inputSourceType, tenantId, inputParameters);
    }

    /**
     * Joins whichever of "Repository: {url}" (with a "Branch: {branch}"
     * line folded into that same section when given), the resolved input
     * blob, and the free-text prompt are actually non-blank, in that
     * order, with a blank line between sections -- never a leftover
     * "Repository: " with nothing after it, never a trailing blank line
     * from a skipped section. Telling the model the branch explicitly here
     * is deliberately more reliable than expecting it to notice a branch
     * name mentioned in free-text prompt. repositoryBranch is ignored when
     * repositoryUrl itself is blank -- a branch with no repository doesn't
     * mean anything. When repositoryUrl/resolvedInput are both blank (no
     * inputSourceType, no repo given), this reduces to exactly `prompt` --
     * the pre-existing behavior for general-assistant-style agents is
     * untouched.
     */
    private String assemblePrompt(String repositoryUrl, String repositoryBranch, String resolvedInput, String prompt) {
        List<String> sections = new ArrayList<>();
        if (repositoryUrl != null && !repositoryUrl.isBlank()) {
            String repositorySection = "Repository: " + repositoryUrl;
            if (repositoryBranch != null && !repositoryBranch.isBlank()) {
                repositorySection += "\nBranch: " + repositoryBranch;
            }
            sections.add(repositorySection);
        }
        if (resolvedInput != null && !resolvedInput.isBlank()) {
            sections.add(resolvedInput);
        }
        if (prompt != null && !prompt.isBlank()) {
            sections.add(prompt);
        }
        return String.join("\n\n", sections);
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

    /**
     * userId is nullable in signature only for the legacy call sites this
     * class's own backward-compat run() overloads still support (tests
     * predating triggeredBy) -- a real execution always has one now that
     * credentials are per-user (see V22/V23). A null userId here always
     * fails: there is deliberately no tenant-wide fallback credential.
     */
    private String resolveApiKey(UUID tenantId, UUID userId, LlmProvider provider) {
        if (userId == null) {
            throw new AgentException(HttpStatus.BAD_REQUEST,
                    "This execution has no triggering user recorded -- cannot resolve a per-user " + provider + " credential.");
        }
        VendorCredential credential = vendorCredentialRepository.findByTenantIdAndUserIdAndProvider(tenantId, userId, provider.name())
                .filter(VendorCredential::isActive)
                .orElseThrow(() -> new AgentException(HttpStatus.BAD_REQUEST,
                        "No active " + provider + " credential configured for you -- PUT /vendor-credentials first"));
        return vendorCredentialService.decryptToken(credential);
    }
}
