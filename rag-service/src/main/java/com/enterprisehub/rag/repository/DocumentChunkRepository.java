package com.enterprisehub.rag.repository;

import com.enterprisehub.rag.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The two candidate queries HybridScoreMerger combines -- one per signal,
 * merged in Java rather than one combined SQL ORDER BY expression, which is
 * what makes the merge/re-rank step independently unit-testable (see
 * HybridScoreMergerTest). Both use @Query(nativeQuery = true) directly on a
 * JpaRepository method, matching the one existing precedent for anything
 * beyond a derived query method in this codebase (AgentExecutionRepository.
 * claimNextQueued()'s `FOR UPDATE SKIP LOCKED`) -- no JdbcTemplate anywhere
 * else in the app, so this doesn't introduce a second data-access style.
 *
 * Each query returns just (chunk id, raw score) via an interface projection
 * rather than full DocumentChunk rows -- HybridScoreMerger only needs the
 * score to merge/re-rank; RetrievalServiceImpl fetches the actual entities
 * for whichever ids survive the merge, once, instead of twice (one query per
 * candidate list) hydrating full rows that might get discarded anyway.
 *
 * queryVector is the pgvector text literal form (e.g. "[0.1,0.2,...]",
 * PGvector#toString()) passed as a plain String and CAST in SQL, not bound
 * as a typed object -- keeps parameter binding unambiguous regardless of
 * Hibernate's native-query type inference for a custom UserType.
 */
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    interface VectorScoreRow {
        UUID getId();

        /** Cosine distance from pgvector's `<=>` operator -- LOWER is better. */
        Double getDistance();
    }

    interface TextScoreRow {
        UUID getId();

        /** Postgres ts_rank -- HIGHER is better. */
        Double getRank();
    }

    @Query(value = "SELECT id, embedding <=> CAST(:queryVector AS vector) AS distance FROM document_chunk "
            + "WHERE knowledge_source_id = :knowledgeSourceId "
            + "ORDER BY distance ASC "
            + "LIMIT :limit", nativeQuery = true)
    List<VectorScoreRow> findNearestByEmbedding(@Param("knowledgeSourceId") UUID knowledgeSourceId,
                                                 @Param("queryVector") String queryVector,
                                                 @Param("limit") int limit);

    @Query(value = "SELECT id, ts_rank(to_tsvector('english', content), plainto_tsquery('english', :queryText)) AS rank FROM document_chunk "
            + "WHERE knowledge_source_id = :knowledgeSourceId "
            + "AND to_tsvector('english', content) @@ plainto_tsquery('english', :queryText) "
            + "ORDER BY rank DESC "
            + "LIMIT :limit", nativeQuery = true)
    List<TextScoreRow> findByFullTextSearch(@Param("knowledgeSourceId") UUID knowledgeSourceId,
                                             @Param("queryText") String queryText,
                                             @Param("limit") int limit);

    long countByKnowledgeSourceId(UUID knowledgeSourceId);

    void deleteByKnowledgeSourceId(UUID knowledgeSourceId);
}
