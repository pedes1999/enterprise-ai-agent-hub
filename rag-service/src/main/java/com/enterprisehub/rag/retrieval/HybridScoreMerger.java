package com.enterprisehub.rag.retrieval;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Merges two independently-scored candidate sets (pgvector cosine distance,
 * Postgres full-text ts_rank) into one ranked list. Deliberately a pure
 * function over plain (id, score) pairs -- no DB, no Spring, no entity types
 * -- so it's testable with hand-built data (see HybridScoreMergerTest)
 * instead of needing a real Postgres connection to exercise. This is what
 * "hybrid search" (semantic recall via vector similarity, exact-term
 * precision via full-text search -- see the README's RAG architecture
 * section for the fuller rationale) actually resolves to once both signals
 * are back from Postgres.
 *
 * Each signal is min-max normalized to [0, 1] WITHIN ITS OWN candidate set
 * before combining -- vector distance and ts_rank live on completely
 * different, incomparable scales, so a raw weighted sum across them would be
 * meaningless. A chunk missing from one candidate set (found by full-text
 * search but not among the nearest vectors, or vice versa) contributes 0 for
 * that signal rather than being dropped -- it still had a real hit in the
 * other one.
 */
public class HybridScoreMerger {

    public record Candidate(UUID chunkId, double rawScore) {
    }

    public record ScoredChunk(UUID chunkId, double score) {
    }

    private final double vectorWeight;
    private final double textWeight;

    /** 0.6/0.4 favors semantic recall slightly over exact-term precision -- a reasonable default, not a tuned constant; see README. */
    public HybridScoreMerger() {
        this(0.6, 0.4);
    }

    public HybridScoreMerger(double vectorWeight, double textWeight) {
        if (vectorWeight < 0 || textWeight < 0 || vectorWeight + textWeight <= 0) {
            throw new IllegalArgumentException("weights must be non-negative and sum to more than zero");
        }
        this.vectorWeight = vectorWeight;
        this.textWeight = textWeight;
    }

    /** vectorCandidates' rawScore is a cosine DISTANCE (lower is better); textCandidates' rawScore is a ts_rank (higher is better) -- see DocumentChunkRepository's two query methods. */
    public List<ScoredChunk> merge(List<Candidate> vectorCandidates, List<Candidate> textCandidates, int topK) {
        Map<UUID, Double> vectorScores = normalize(vectorCandidates, true);
        Map<UUID, Double> textScores = normalize(textCandidates, false);

        Set<UUID> allIds = new LinkedHashSet<>();
        allIds.addAll(vectorScores.keySet());
        allIds.addAll(textScores.keySet());

        return allIds.stream()
                .map(id -> new ScoredChunk(id,
                        vectorWeight * vectorScores.getOrDefault(id, 0.0) + textWeight * textScores.getOrDefault(id, 0.0)))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(Math.max(topK, 0))
                .toList();
    }

    /** lowerIsBetter=true inverts the [0,1] result (vector distance); false leaves it as-is (ts_rank). A single-candidate or all-tied set normalizes to 1.0 for everyone -- nothing to distinguish them on, so treat them as equally relevant rather than arbitrarily picking a winner. */
    private Map<UUID, Double> normalize(List<Candidate> candidates, boolean lowerIsBetter) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        double min = candidates.stream().mapToDouble(Candidate::rawScore).min().orElseThrow();
        double max = candidates.stream().mapToDouble(Candidate::rawScore).max().orElseThrow();

        Map<UUID, Double> result = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            double normalized = max == min
                    ? 1.0
                    : (candidate.rawScore() - min) / (max - min);
            if (lowerIsBetter && max != min) {
                normalized = 1.0 - normalized;
            }
            // A chunk id shouldn't appear twice in one candidate list (each
            // query returns distinct rows), but keep the best score if it
            // somehow does rather than letting iteration order silently
            // decide.
            result.merge(candidate.chunkId(), normalized, Math::max);
        }
        return result;
    }
}
