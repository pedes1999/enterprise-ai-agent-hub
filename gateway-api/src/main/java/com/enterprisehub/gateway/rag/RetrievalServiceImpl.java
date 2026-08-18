package com.enterprisehub.gateway.rag;

import com.enterprisehub.rag.entity.DocumentChunk;
import com.enterprisehub.rag.repository.DocumentChunkRepository;
import com.enterprisehub.rag.retrieval.HybridScoreMerger;
import com.enterprisehub.rag.retrieval.RetrievalQueryService;
import com.enterprisehub.rag.retrieval.RetrievedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The gateway-api side of RetrievalQueryService (see its javadoc for why the
 * interface lives in rag-service but the implementation doesn't) -- needs
 * EmbeddingProviderResolver (credential access) and DocumentChunkRepository
 * (rag-service) together, which only gateway-api is allowed to depend on
 * both of.
 *
 * Pulls a wider candidate pool (CANDIDATE_POOL_SIZE) from each of the two
 * signals than the caller's requested topK before merging -- HybridScoreMerger
 * needs enough candidates from BOTH sides to normalize against and actually
 * find overlap; asking each query for only topK rows up front would silently
 * bias results toward whichever signal happens to be checked first.
 */
@Service
public class RetrievalServiceImpl implements RetrievalQueryService {

    private static final int CANDIDATE_POOL_SIZE = 20;

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingProviderResolver embeddingProviderResolver;
    private final HybridScoreMerger hybridScoreMerger = new HybridScoreMerger();

    public RetrievalServiceImpl(DocumentChunkRepository documentChunkRepository, EmbeddingProviderResolver embeddingProviderResolver) {
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingProviderResolver = embeddingProviderResolver;
    }

    @Override
    public List<RetrievedChunk> query(String tenantId, String userId, UUID knowledgeSourceId, String queryText, int topK) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        EmbeddingModel embeddingModel = embeddingProviderResolver.resolve(
                UUID.fromString(tenantId), userId == null ? null : UUID.fromString(userId));
        float[] queryVector = embeddingModel.embed(queryText).content().vector();
        String vectorLiteral = toVectorLiteral(queryVector);

        List<HybridScoreMerger.Candidate> vectorCandidates = documentChunkRepository
                .findNearestByEmbedding(knowledgeSourceId, vectorLiteral, CANDIDATE_POOL_SIZE).stream()
                .map(row -> new HybridScoreMerger.Candidate(row.getId(), row.getDistance()))
                .toList();
        List<HybridScoreMerger.Candidate> textCandidates = documentChunkRepository
                .findByFullTextSearch(knowledgeSourceId, queryText, CANDIDATE_POOL_SIZE).stream()
                .map(row -> new HybridScoreMerger.Candidate(row.getId(), row.getRank()))
                .toList();

        List<HybridScoreMerger.ScoredChunk> merged = hybridScoreMerger.merge(vectorCandidates, textCandidates, topK);
        if (merged.isEmpty()) {
            return List.of();
        }

        Map<UUID, DocumentChunk> chunksById = documentChunkRepository
                .findAllById(merged.stream().map(HybridScoreMerger.ScoredChunk::chunkId).toList()).stream()
                .collect(Collectors.toMap(DocumentChunk::getId, Function.identity()));

        return merged.stream()
                .map(scored -> toRetrievedChunk(scored, chunksById.get(scored.chunkId())))
                .filter(Objects::nonNull)
                .toList();
    }

    /** chunk is null only if it was deleted between the merge query and this hydration -- skip rather than fail the whole retrieval over a race. */
    private RetrievedChunk toRetrievedChunk(HybridScoreMerger.ScoredChunk scored, DocumentChunk chunk) {
        if (chunk == null) {
            return null;
        }
        return new RetrievedChunk(chunk.getId().toString(), chunk.getDocumentName(), chunk.getContent(), scored.score());
    }

    /**
     * pgvector's own text input format: "[v1,v2,...]", no whitespace. Built
     * by hand instead of via PGvector#toString() (which would work
     * identically) so gateway-api never needs org.postgresql:postgresql on
     * its COMPILE classpath just for this -- that dependency is deliberately
     * runtime-scope only here (see the pom), and VectorType (rag-service) is
     * the one place PGvector's actual JDBC binding happens, at persistence
     * time, not query-parameter-building time.
     */
    private String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}
