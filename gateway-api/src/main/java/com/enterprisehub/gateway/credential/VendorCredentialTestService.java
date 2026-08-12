package com.enterprisehub.gateway.credential;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.dto.CredentialTestResult;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Backs POST /vendor-credentials/test. Only ANTHROPIC is actually wired up
 * (see LlmEngineFactory) -- OPENAI/GEMINI return a clear "not supported
 * yet" result rather than a confusing 500 from the underlying
 * UnsupportedOperationException.
 *
 * The "cheap" validation call is still a REAL, billed Anthropic API call
 * (a trivial prompt asking for a one-word reply) -- there is no free way
 * to validate an API key against Anthropic's API short of actually calling
 * it. Deliberately real, not a network-level "is this well-formed" check,
 * since a syntactically valid but revoked/wrong key needs to fail here.
 */
@Service
public class VendorCredentialTestService {

    private final VendorCredentialRepository repository;
    private final VendorCredentialService vendorCredentialService;
    private final LlmEngineFactory llmEngineFactory;
    private final LlmProperties llmProperties;

    public VendorCredentialTestService(VendorCredentialRepository repository, VendorCredentialService vendorCredentialService,
                                        LlmEngineFactory llmEngineFactory, LlmProperties llmProperties) {
        this.repository = repository;
        this.vendorCredentialService = vendorCredentialService;
        this.llmEngineFactory = llmEngineFactory;
        this.llmProperties = llmProperties;
    }

    public CredentialTestResult test(UUID tenantId, String providerValue) {
        VendorProvider provider = VendorProvider.parse(providerValue)
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.BAD_REQUEST,
                        "provider must be one of ANTHROPIC, OPENAI, GEMINI"));

        if (provider != VendorProvider.ANTHROPIC) {
            return new CredentialTestResult(false, "Test connection is not supported for " + provider.name() + " yet.");
        }

        VendorCredential credential = repository.findByTenantIdAndProvider(tenantId, provider.name())
                .filter(VendorCredential::isActive)
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.NOT_FOUND,
                        "No active credential stored for provider " + provider.name()));

        String apiKey = vendorCredentialService.decryptToken(credential);

        try {
            ChatLanguageModel model = llmEngineFactory.create(LlmProvider.ANTHROPIC, apiKey, llmProperties.anthropicModelName());
            model.generate("Reply with exactly one word: OK");
        } catch (RuntimeException e) {
            return new CredentialTestResult(false, "Anthropic rejected this credential: " + e.getMessage());
        }

        vendorCredentialService.markValidated(tenantId, provider.name());
        return new CredentialTestResult(true, "Anthropic credential is valid.");
    }
}
