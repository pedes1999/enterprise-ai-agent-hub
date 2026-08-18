package com.enterprisehub.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridScoreMergerTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    @Test
    void merge_bothCandidateListsEmpty_returnsEmptyList() {
        HybridScoreMerger merger = new HybridScoreMerger();

        List<HybridScoreMerger.ScoredChunk> result = merger.merge(List.of(), List.of(), 5);

        assertThat(result).isEmpty();
    }

    @Test
    void merge_lowerVectorDistanceRanksHigher() {
        HybridScoreMerger merger = new HybridScoreMerger(1.0, 0.0);
        List<HybridScoreMerger.Candidate> vectorCandidates = List.of(
                new HybridScoreMerger.Candidate(A, 0.1),  // closest -- best
                new HybridScoreMerger.Candidate(B, 0.9)); // farthest -- worst

        List<HybridScoreMerger.ScoredChunk> result = merger.merge(vectorCandidates, List.of(), 5);

        assertThat(result).extracting(HybridScoreMerger.ScoredChunk::chunkId).containsExactly(A, B);
        assertThat(result.get(0).score()).isGreaterThan(result.get(1).score());
    }

    @Test
    void merge_higherTextRankRanksHigher() {
        HybridScoreMerger merger = new HybridScoreMerger(0.0, 1.0);
        List<HybridScoreMerger.Candidate> textCandidates = List.of(
                new HybridScoreMerger.Candidate(A, 0.9),  // highest rank -- best
                new HybridScoreMerger.Candidate(B, 0.1)); // lowest rank -- worst

        List<HybridScoreMerger.ScoredChunk> result = merger.merge(List.of(), textCandidates, 5);

        assertThat(result).extracting(HybridScoreMerger.ScoredChunk::chunkId).containsExactly(A, B);
    }

    @Test
    void merge_chunkInBothCandidateSets_scoresHigherThanEitherSignalAlone() {
        HybridScoreMerger merger = new HybridScoreMerger(0.5, 0.5);
        // A is a strong hit on BOTH signals; B is a strong hit on vector only.
        List<HybridScoreMerger.Candidate> vectorCandidates = List.of(
                new HybridScoreMerger.Candidate(A, 0.0),
                new HybridScoreMerger.Candidate(B, 0.0));
        List<HybridScoreMerger.Candidate> textCandidates = List.of(
                new HybridScoreMerger.Candidate(A, 1.0));

        List<HybridScoreMerger.ScoredChunk> result = merger.merge(vectorCandidates, textCandidates, 5);

        assertThat(result.get(0).chunkId()).isEqualTo(A);
        double scoreA = result.stream().filter(c -> c.chunkId().equals(A)).findFirst().orElseThrow().score();
        double scoreB = result.stream().filter(c -> c.chunkId().equals(B)).findFirst().orElseThrow().score();
        assertThat(scoreA).isGreaterThan(scoreB);
    }

    @Test
    void merge_chunkMissingFromOneSignal_stillIncludedWithZeroContributionFromThatSignal() {
        HybridScoreMerger merger = new HybridScoreMerger(0.6, 0.4);
        List<HybridScoreMerger.Candidate> vectorCandidates = List.of(new HybridScoreMerger.Candidate(A, 0.2));
        List<HybridScoreMerger.Candidate> textCandidates = List.of(new HybridScoreMerger.Candidate(B, 0.8));

        List<HybridScoreMerger.ScoredChunk> result = merger.merge(vectorCandidates, textCandidates, 5);

        assertThat(result).extracting(HybridScoreMerger.ScoredChunk::chunkId).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void merge_singleCandidateOrAllTied_normalizesToOneRatherThanPickingArbitraryWinner() {
        HybridScoreMerger merger = new HybridScoreMerger(1.0, 0.0);
        List<HybridScoreMerger.Candidate> vectorCandidates = List.of(
                new HybridScoreMerger.Candidate(A, 0.5),
                new HybridScoreMerger.Candidate(B, 0.5));

        List<HybridScoreMerger.ScoredChunk> result = merger.merge(vectorCandidates, List.of(), 5);

        assertThat(result).extracting(HybridScoreMerger.ScoredChunk::score).containsExactly(1.0, 1.0);
    }

    @Test
    void merge_topKLimitsResultSize() {
        HybridScoreMerger merger = new HybridScoreMerger();
        List<HybridScoreMerger.Candidate> vectorCandidates = List.of(
                new HybridScoreMerger.Candidate(A, 0.1),
                new HybridScoreMerger.Candidate(B, 0.2),
                new HybridScoreMerger.Candidate(C, 0.3));

        List<HybridScoreMerger.ScoredChunk> result = merger.merge(vectorCandidates, List.of(), 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void constructor_rejectsInvalidWeights() {
        assertThatThrownBy(() -> new HybridScoreMerger(-0.1, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HybridScoreMerger(0.0, 0.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
