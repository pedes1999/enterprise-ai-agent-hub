package com.enterprisehub.gateway.agent;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.dto.AgentPingResponse;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.credential.VendorCredentialService;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Week 4 spike: proves the full chain end to end -- encrypted vendor
 * credential in Postgres -> decrypted at request time -> real LangChain4j
 * client -> real Anthropic API call -> response back to the caller. This is
 * deliberately NOT the real agent execution model (no SharedExecutionContext,
 * no tools, no async job, nothing persisted to agent_executions); it exists
 * to validate the shape of LlmEngineFactory and the credential-decryption
 * path against one working provider before building that out for real.
 */
@Service
public class AgentPingService {

    private final VendorCredentialRepository vendorCredentialRepository;
    private final VendorCredentialService vendorCredentialService;
    private final LlmEngineFactory llmEngineFactory;
    private final LlmProperties llmProperties;

    public AgentPingService(VendorCredentialRepository vendorCredentialRepository,
                             VendorCredentialService vendorCredentialService,
                             LlmEngineFactory llmEngineFactory,
                             LlmProperties llmProperties) {
        this.vendorCredentialRepository = vendorCredentialRepository;
        this.vendorCredentialService = vendorCredentialService;
        this.llmEngineFactory = llmEngineFactory;
        this.llmProperties = llmProperties;
    }

    public AgentPingResponse ping(UUID tenantId, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "prompt is required");
        }

        VendorCredential credential = vendorCredentialRepository.findByTenantIdAndProvider(tenantId, LlmProvider.ANTHROPIC.name())
                .filter(VendorCredential::isActive)
                .orElseThrow(() -> new AgentException(HttpStatus.BAD_REQUEST,
                        "No active ANTHROPIC credential configured for this tenant -- PUT /vendor-credentials first"));

        String apiKey = vendorCredentialService.decryptToken(credential);
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
}
