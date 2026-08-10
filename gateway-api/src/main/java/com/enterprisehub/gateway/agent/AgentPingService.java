package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.dto.AgentPingResponse;
import com.enterprisehub.dto.AgentToolPingResponse;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.credential.VendorCredentialService;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Week 4 spike (ping): proves the full chain end to end -- encrypted
 * vendor credential in Postgres -> decrypted at request time -> real
 * LangChain4j client -> real Anthropic API call -> response back to the
 * caller. pingWithTools (Week 5-6 spike) is now a thin synchronous wrapper
 * around AgentPromptRunner, which also backs AgentJobWorker's async path
 * -- this class itself no longer builds tools or a SharedExecutionContext
 * directly. Still deliberately NOT the real agent execution model for
 * `ping`: no persisted agent_executions row, synchronous only. See
 * AgentExecutionService/AgentJobWorker for the real (async, persisted,
 * queued) path.
 */
@Service
public class AgentPingService {

    private final VendorCredentialRepository vendorCredentialRepository;
    private final VendorCredentialService vendorCredentialService;
    private final LlmEngineFactory llmEngineFactory;
    private final LlmProperties llmProperties;
    private final AgentPromptRunner agentPromptRunner;

    public AgentPingService(VendorCredentialRepository vendorCredentialRepository,
                             VendorCredentialService vendorCredentialService,
                             LlmEngineFactory llmEngineFactory,
                             LlmProperties llmProperties,
                             AgentPromptRunner agentPromptRunner) {
        this.vendorCredentialRepository = vendorCredentialRepository;
        this.vendorCredentialService = vendorCredentialService;
        this.llmEngineFactory = llmEngineFactory;
        this.llmProperties = llmProperties;
        this.agentPromptRunner = agentPromptRunner;
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

        // Synthetic id -- this spike endpoint still doesn't create a real
        // agent_executions row; it stays purely synchronous. Once you want
        // this to be durable/async, use POST /agents/execute instead (see
        // AgentExecutionService/AgentJobWorker).
        String executionId = UUID.randomUUID().toString();

        ToolCallingChatEngine.ToolChatResult result;
        try {
            result = agentPromptRunner.run(tenantId, executionId, prompt);
        } catch (RuntimeException e) {
            throw new AgentException(HttpStatus.BAD_GATEWAY, "Anthropic API call failed: " + e.getMessage());
        }

        return new AgentToolPingResponse(LlmProvider.ANTHROPIC.name(), agentPromptRunner.modelName(), result.reply(), result.toolWasUsed());
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
