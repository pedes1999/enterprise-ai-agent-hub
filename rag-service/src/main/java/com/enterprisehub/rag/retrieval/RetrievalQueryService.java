package com.enterprisehub.rag.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * The seam RetrievalTool calls through without depending on gateway-api
 * (which owns the actual implementation, RetrievalServiceImpl -- it needs
 * VendorCredentialService + agent-core's EmbeddingModelFactory together,
 * and gateway-api is the only module allowed to depend on everything; see
 * the RAG module's plan). Same dependency-inversion shape already used for
 * CredentialResolver being handed into agent-runtime's sandboxed tools.
 */
public interface RetrievalQueryService {

    /**
     * userId resolves whose OpenAI/Gemini vendor credential embeds the
     * query text -- vendor credentials are per-user (see
     * V22__vendor_credentials_per_user.sql), so there is no tenant-wide
     * fallback here either, matching AgentPromptRunner.resolveApiKey()'s
     * existing behavior for the chat model.
     */
    List<RetrievedChunk> query(String tenantId, String userId, UUID knowledgeSourceId, String queryText, int topK);
}
