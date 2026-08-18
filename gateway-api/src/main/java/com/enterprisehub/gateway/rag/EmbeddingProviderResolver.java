package com.enterprisehub.gateway.rag;

import com.enterprisehub.core.llm.EmbeddingModelFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.credential.VendorCredentialService;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared by IngestionService and RetrievalServiceImpl: resolves whichever of
 * a user's own OpenAI, Gemini, or Local vendor credential is active (in that
 * order), matching the "no tenant-wide fallback, per-user credential" rule
 * AgentPromptRunner.resolveApiKey() already enforces for the chat model.
 * OpenAI/Gemini are checked first and remain the recommended default for
 * real multi-tenant use -- LOCAL is last because a Local credential doesn't
 * mean "this tenant's own key," it means "whatever's running on the
 * server's own machine," which only makes sense for single-tenant dev/demo
 * setups (see EmbeddingModelFactory's javadoc).
 */
@Component
public class EmbeddingProviderResolver {

    private static final List<String> SUPPORTED_PROVIDERS = List.of("OPENAI", "GEMINI", "LOCAL");

    private final VendorCredentialRepository vendorCredentialRepository;
    private final VendorCredentialService vendorCredentialService;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final LlmProperties llmProperties;

    public EmbeddingProviderResolver(VendorCredentialRepository vendorCredentialRepository,
                                      VendorCredentialService vendorCredentialService,
                                      EmbeddingModelFactory embeddingModelFactory,
                                      LlmProperties llmProperties) {
        this.vendorCredentialRepository = vendorCredentialRepository;
        this.vendorCredentialService = vendorCredentialService;
        this.embeddingModelFactory = embeddingModelFactory;
        this.llmProperties = llmProperties;
    }

    public EmbeddingModel resolve(UUID tenantId, UUID userId) {
        if (userId == null) {
            throw new RagException(HttpStatus.BAD_REQUEST,
                    "This request has no authenticated user recorded -- cannot resolve a per-user embedding credential.");
        }
        for (String providerName : SUPPORTED_PROVIDERS) {
            Optional<VendorCredential> credential = vendorCredentialRepository
                    .findByTenantIdAndUserIdAndProvider(tenantId, userId, providerName)
                    .filter(VendorCredential::isActive);
            if (credential.isPresent()) {
                LlmProvider provider = LlmProvider.valueOf(providerName);
                String apiKey = vendorCredentialService.decryptToken(credential.get());
                return embeddingModelFactory.create(provider, apiKey, llmProperties.baseUrl(provider));
            }
        }
        throw new RagException(HttpStatus.BAD_REQUEST,
                "RAG features need an active OpenAI, Gemini, or Local credential -- PUT /vendor-credentials first.");
    }
}
