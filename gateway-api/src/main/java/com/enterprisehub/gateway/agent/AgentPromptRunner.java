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
import com.enterprisehub.runtime.tools.GitCloneTool;
import com.enterprisehub.runtime.tools.RunShellCommandTool;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The actual "run this prompt, with tools, for this tenant" logic --
 * extracted out of AgentPingService so both the synchronous spike endpoint
 * (/agents/ping-with-tools) and AgentJobWorker's async path share exactly
 * one implementation of tool assembly and context building. Deliberately
 * has no notion of HTTP requests or agent_executions rows -- callers own
 * translating its result (or a thrown exception) into whichever response
 * shape they need.
 */
@Service
public class AgentPromptRunner {

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

        List<AgentTool> tools = List.of(
                new CurrentDateTimeTool(),
                new RunShellCommandTool(sandboxClient, toolExecutionListener),
                new GitCloneTool(sandboxClient, toolExecutionListener, credentialResolver));
        SharedExecutionContext context = sharedExecutionContextFactory.create(
                tenantId.toString(), executionId, LlmProvider.ANTHROPIC, apiKey, modelName, tools);

        return context.chat(prompt);
    }

    private String resolveApiKey(UUID tenantId) {
        VendorCredential credential = vendorCredentialRepository.findByTenantIdAndProvider(tenantId, LlmProvider.ANTHROPIC.name())
                .filter(VendorCredential::isActive)
                .orElseThrow(() -> new AgentException(HttpStatus.BAD_REQUEST,
                        "No active ANTHROPIC credential configured for this tenant -- PUT /vendor-credentials first"));
        return vendorCredentialService.decryptToken(credential);
    }
}
