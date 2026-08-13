package com.enterprisehub.gateway.credential;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.dto.CredentialTestResult;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.gateway.tenant.TenantLlmProviderResolver;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Backs POST /vendor-credentials/test. Every VendorProvider is wired up
 * now (see LlmEngineFactory) -- the "cheap" validation call is still a
 * REAL, billed API call to whichever vendor (a trivial prompt asking for a
 * one-word reply), since there's no free way to validate an API key short
 * of actually calling it. Deliberately real, not a network-level "is this
 * well-formed" check, since a syntactically valid but revoked/wrong key
 * needs to fail here.
 */
@Service
public class VendorCredentialTestService {

    private final VendorCredentialRepository repository;
    private final VendorCredentialService vendorCredentialService;
    private final LlmEngineFactory llmEngineFactory;
    private final LlmProperties llmProperties;
    private final TenantLlmProviderResolver tenantLlmProviderResolver;

    public VendorCredentialTestService(VendorCredentialRepository repository, VendorCredentialService vendorCredentialService,
                                        LlmEngineFactory llmEngineFactory, LlmProperties llmProperties,
                                        TenantLlmProviderResolver tenantLlmProviderResolver) {
        this.repository = repository;
        this.vendorCredentialService = vendorCredentialService;
        this.llmEngineFactory = llmEngineFactory;
        this.llmProperties = llmProperties;
        this.tenantLlmProviderResolver = tenantLlmProviderResolver;
    }

    public CredentialTestResult test(UUID tenantId, String providerValue) {
        VendorProvider provider = VendorProvider.parse(providerValue)
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.BAD_REQUEST,
                        "provider must be one of ANTHROPIC, OPENAI, GEMINI, LOCAL"));

        VendorCredential credential = repository.findByTenantIdAndProvider(tenantId, provider.name())
                .filter(VendorCredential::isActive)
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.NOT_FOUND,
                        "No active credential stored for provider " + provider.name()));

        String apiKey = vendorCredentialService.decryptToken(credential);
        // VendorProvider/LlmProvider are kept in sync by convention (same names) -- see LlmProvider's javadoc.
        LlmProvider llmProvider = LlmProvider.valueOf(provider.name());
        // Resolve the model the same way a real execution would (tenant's
        // preferredModelName override first, falling back to the server
        // default) -- testing against the server default unconditionally
        // meant this could fail (or silently pass) against a model the
        // tenant isn't actually configured to use, e.g. a LOCAL/Ollama
        // tenant who pulled "llama3.1:8b" but never touched the server-wide
        // default of "llama3.1", which Ollama treats as a different,
        // unpulled model.
        String modelName = tenantLlmProviderResolver.resolveModelName(tenantId, llmProvider);

        try {
            ChatLanguageModel model = llmEngineFactory.create(llmProvider, apiKey,
                    modelName, llmProperties.baseUrl(llmProvider));
            model.generate("Reply with exactly one word: OK");
        } catch (RuntimeException e) {
            return new CredentialTestResult(false, provider + " rejected this credential: " + e.getMessage());
        }

        vendorCredentialService.markValidated(tenantId, provider.name());
        return new CredentialTestResult(true, provider + " credential is valid.");
    }
}
