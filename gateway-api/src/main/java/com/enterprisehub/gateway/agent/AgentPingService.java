package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.SharedExecutionContext;
import com.enterprisehub.core.SharedExecutionContextFactory;
import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.dto.AgentPingResponse;
import com.enterprisehub.dto.AgentToolPingResponse;
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
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Week 4 spike (ping) + Week 5-6 spike (pingWithTools): proves the full
 * chain end to end -- encrypted vendor credential in Postgres -> decrypted
 * at request time -> real LangChain4j client -> real Anthropic API call ->
 * response back to the caller, and now also -> SharedExecutionContext ->
 * tool-calling loop -> a real tool actually invoked, including real
 * sandboxed ones (RunShellCommandTool and GitCloneTool, via agent-runtime's
 * SandboxClient -> sidecar -> E2B; GitCloneTool additionally resolves the
 * tenant's GIT tool_credentials via CredentialResolver). Every tool call is
 * audited to tool_executions
 * regardless (JpaToolExecutionListener), but this is still deliberately
 * NOT the real agent execution model: no async job, nothing persisted to
 * agent_executions itself, no repository/workspace context. It exists to
 * validate the shape of each new piece against one working provider before
 * building the real thing.
 */
@Service
public class AgentPingService {

    private final VendorCredentialRepository vendorCredentialRepository;
    private final VendorCredentialService vendorCredentialService;
    private final LlmEngineFactory llmEngineFactory;
    private final SharedExecutionContextFactory sharedExecutionContextFactory;
    private final LlmProperties llmProperties;
    private final SandboxClient sandboxClient;
    private final ToolExecutionListener toolExecutionListener;
    private final CredentialResolver credentialResolver;

    public AgentPingService(VendorCredentialRepository vendorCredentialRepository,
                             VendorCredentialService vendorCredentialService,
                             LlmEngineFactory llmEngineFactory,
                             SharedExecutionContextFactory sharedExecutionContextFactory,
                             LlmProperties llmProperties,
                             SandboxClient sandboxClient,
                             ToolExecutionListener toolExecutionListener,
                             CredentialResolver credentialResolver) {
        this.vendorCredentialRepository = vendorCredentialRepository;
        this.vendorCredentialService = vendorCredentialService;
        this.llmEngineFactory = llmEngineFactory;
        this.sharedExecutionContextFactory = sharedExecutionContextFactory;
        this.llmProperties = llmProperties;
        this.sandboxClient = sandboxClient;
        this.toolExecutionListener = toolExecutionListener;
        this.credentialResolver = credentialResolver;
    }

    public AgentPingResponse ping(UUID tenantId, String prompt) {
        validatePrompt(prompt);
        String apiKey = resolveApiKey(tenantId);
        String modelName = llmProperties.anthropicModelName();
        ChatLanguageModel model = llmEngineFactory.create(LlmProvider.ANTHROPIC, apiKey, modelName);

        String reply;
        try {
            reply = model.generate(prompt);
        } catch (RuntimeException e) {
            throw new AgentException(HttpStatus.BAD_GATEWAY, "Anthropic API call failed: " + e.getMessage());
        }

        return new AgentPingResponse(LlmProvider.ANTHROPIC.name(), modelName, reply);
    }

    public AgentToolPingResponse pingWithTools(UUID tenantId, String prompt) {
        validatePrompt(prompt);
        String apiKey = resolveApiKey(tenantId);
        String modelName = llmProperties.anthropicModelName();

        // Synthetic id -- these spike endpoints don't create a real
        // agent_executions row (see the class javadoc). Once real agent
        // orchestration exists (Weeks 9-10), this becomes that row's id.
        String executionId = UUID.randomUUID().toString();
        List<AgentTool> tools = List.of(
                new CurrentDateTimeTool(),
                new RunShellCommandTool(sandboxClient, toolExecutionListener),
                new GitCloneTool(sandboxClient, toolExecutionListener, credentialResolver));
        SharedExecutionContext context = sharedExecutionContextFactory.create(
                tenantId.toString(), executionId, LlmProvider.ANTHROPIC, apiKey, modelName, tools);

        ToolCallingChatEngine.ToolChatResult result;
        try {
            result = context.chat(prompt);
        } catch (RuntimeException e) {
            throw new AgentException(HttpStatus.BAD_GATEWAY, "Anthropic API call failed: " + e.getMessage());
        }

        return new AgentToolPingResponse(LlmProvider.ANTHROPIC.name(), modelName, result.reply(), result.toolWasUsed());
    }

    private void validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "prompt is required");
        }
    }

    private String resolveApiKey(UUID tenantId) {
        if (tenantId == null) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "tenantId is required");
        }
        VendorCredential credential = vendorCredentialRepository.findByTenantIdAndProvider(tenantId, LlmProvider.ANTHROPIC.name())
                .filter(VendorCredential::isActive)
                .orElseThrow(() -> new AgentException(HttpStatus.BAD_REQUEST,
                        "No active ANTHROPIC credential configured for this tenant -- PUT /vendor-credentials first"));
        return vendorCredentialService.decryptToken(credential);
    }
}
