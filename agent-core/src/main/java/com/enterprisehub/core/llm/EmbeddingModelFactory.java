package com.enterprisehub.core.llm;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

/**
 * Sibling to LlmEngineFactory, same seam: caller supplies WHICH provider and
 * the tenant's own (already-decrypted) API key, gets back a provider-agnostic
 * LangChain4j EmbeddingModel. Deliberately NOT a Spring @Component, same
 * reason as LlmEngineFactory -- agent-core stays framework-light.
 *
 * OPENAI, GEMINI, and LOCAL are implemented; only ANTHROPIC is excluded (it
 * has no embeddings API at all). LOCAL reuses OpenAiEmbeddingModel pointed
 * at an Ollama-compatible baseUrl, the exact same trick LlmEngineFactory
 * uses for LOCAL chat -- Ollama's /v1/embeddings endpoint speaks the OpenAI
 * wire format. Confirmed live against a real local Ollama instance
 * (nomic-embed-text, which happens to output 768 dimensions natively, no
 * truncation needed) -- useful for running RAG entirely offline/free, but
 * OPENAI/GEMINI (a tenant's own real vendor credential) remain the
 * recommended default for actual multi-tenant use: a LOCAL embedding
 * credential means "whatever's running on the SERVER's own machine", which
 * only makes sense for a single-tenant dev/demo setup, not a real deployment
 * where every tenant would silently share whatever the server operator
 * happens to have running locally.
 *
 * EMBEDDING_DIMENSIONS (768) is fixed and identical across every supported
 * provider -- document_chunk.embedding is a single `vector(768)` column
 * (see V29__knowledge_source_and_document_chunk.sql's javadoc) regardless of
 * which vendor actually produced a given row's embedding, so cosine-distance
 * comparisons between chunks ingested under different tenant settings stay
 * meaningful. OpenAI's text-embedding-3-small natively outputs 1536
 * dimensions but supports truncating via an explicit `dimensions` parameter
 * (a documented, supported OpenAI feature -- matryoshka-style embeddings,
 * not a lossy hack); Gemini's embedding-001 and Ollama's nomic-embed-text
 * both already output 768 natively.
 */
public class EmbeddingModelFactory {

    public static final int EMBEDDING_DIMENSIONS = 768;

    private static final String DEFAULT_OPENAI_EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String DEFAULT_GEMINI_EMBEDDING_MODEL = "text-embedding-004";
    private static final String DEFAULT_LOCAL_EMBEDDING_MODEL = "nomic-embed-text";
    private static final String LOCAL_DEFAULT_BASE_URL = "http://localhost:11434/v1";
    private static final String LOCAL_PLACEHOLDER_API_KEY = "not-needed";

    public EmbeddingModel create(LlmProvider provider, String apiKey) {
        return create(provider, apiKey, null);
    }

    /** baseUrl is only meaningful for LOCAL (defaults to Ollama's standard address if not supplied) -- ignored for every other provider, same contract as LlmEngineFactory.create(). */
    public EmbeddingModel create(LlmProvider provider, String apiKey, String baseUrl) {
        return switch (provider) {
            case OPENAI -> OpenAiEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .modelName(DEFAULT_OPENAI_EMBEDDING_MODEL)
                    .dimensions(EMBEDDING_DIMENSIONS)
                    .build();
            case GEMINI -> GoogleAiEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .modelName(DEFAULT_GEMINI_EMBEDDING_MODEL)
                    .outputDimensionality(EMBEDDING_DIMENSIONS)
                    .build();
            case LOCAL -> OpenAiEmbeddingModel.builder()
                    .baseUrl(baseUrl != null && !baseUrl.isBlank() ? baseUrl : LOCAL_DEFAULT_BASE_URL)
                    .apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : LOCAL_PLACEHOLDER_API_KEY)
                    .modelName(DEFAULT_LOCAL_EMBEDDING_MODEL)
                    .build();
            case ANTHROPIC -> throw new UnsupportedOperationException(
                    "RAG features need an OpenAI, Gemini, or Local vendor credential for embeddings -- "
                            + provider + " has no embeddings API. Connect one under Credentials first.");
        };
    }
}
