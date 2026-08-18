package com.enterprisehub.rag.eval;

import com.enterprisehub.core.llm.EmbeddingModelFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.rag.chunking.ParagraphChunker;
import com.enterprisehub.rag.retrieval.HybridScoreMerger;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A retrieval quality eval, not a correctness unit test -- it exercises the
 * REAL ParagraphChunker and HybridScoreMerger (the two pieces the task asked
 * to be unit-tested and eval'd) against a small fixture document set, and
 * reports precision@k. Skipped by default (no OPENAI_API_KEY in CI or this
 * sandbox) rather than failing the build -- set OPENAI_API_KEY to an active
 * key and re-run to see the report:
 *
 *   OPENAI_API_KEY=sk-... mvn -pl rag-service test -Dtest=RetrievalEvalTest
 *
 * Deliberately DOESN'T stand up a real Postgres+pgvector instance: the two
 * candidate signals (vector distance, text rank) are computed in plain Java
 * memory here -- cosine similarity over real embeddings for the vector
 * signal, a simple keyword-overlap count for the text signal -- instead of
 * DocumentChunkRepository's native SQL. That keeps this eval runnable with
 * nothing but an API key, no docker-compose/DB setup, while still exercising
 * the actual chunker and the actual HybridScoreMerger.merge() logic
 * unchanged; only how the two candidate lists are SOURCED differs from
 * production (RetrievalServiceImpl uses pgvector's `<=>` operator and
 * Postgres's ts_rank instead of hand-rolled cosine/keyword scoring).
 */
class RetrievalEvalTest {

    private record FixtureDocument(String name, String text) {
    }

    private record EvalCase(String query, String expectedDocument, String expectedContentMarker) {
    }

    private static final int TOP_K = 3;

    private static final List<FixtureDocument> FIXTURE_DOCUMENTS = List.of(
            new FixtureDocument("tenant-isolation.md", """
                    Row Level Security Isolation

                    Every tenant-scoped table in this schema has a tenant_id column and a
                    tenant_isolation policy that compares it against current_setting('app.current_tenant_id').
                    Both ENABLE ROW LEVEL SECURITY and FORCE ROW LEVEL SECURITY are applied together,
                    because the table owner role is otherwise exempt from its own RLS policies by default.

                    Setting the Session Variable

                    TenantAwareDataSource wraps the application's single DataSource bean and runs
                    SELECT set_config('app.current_tenant_id', ?, false) on every JDBC connection
                    checkout. This happens unconditionally, even when no tenant is set, so a pooled
                    connection can never leak one tenant's context to the next borrower.

                    Why Not An Aspect

                    An earlier attempt used a Spring AOP aspect to set the session variable per
                    transaction, but that approach was abandoned in favor of the DataSource-level
                    interception, since it covers every connection checkout regardless of whether
                    the code path happens to go through a transactional method.
                    """),
            new FixtureDocument("credential-encryption.md", """
                    Envelope Encryption For Vendor Credentials

                    Vendor API tokens (Anthropic, OpenAI, Gemini) are encrypted at rest using
                    AES-256-GCM before being written to the vendor_credentials table. The
                    ciphertext is stored alongside a key identifier, never the plaintext token
                    and never the raw encryption key itself.

                    Per-User Credentials

                    Each app_user owns their own vendor credential per provider, not a single
                    tenant-wide shared key. There is deliberately no tenant-wide fallback: a user
                    with no credential for a provider simply cannot use it, and execution-time
                    resolution always requires a specific triggering user.

                    Decryption Only At Use Time

                    The plaintext token is only ever reconstructed in memory for the single
                    duration of constructing an LLM client, immediately before an actual API call,
                    and is never logged, cached, or returned over HTTP in any response.
                    """),
            new FixtureDocument("tool-calling-contract.md", """
                    The AgentTool Interface

                    Tools implement a custom AgentTool interface rather than a framework-specific
                    annotation, so that only ToolCallingChatEngine needs to know about the
                    underlying LLM library's tool-calling wire format. Every parameter a tool
                    declares is a plain string -- no nested objects, no typed parameters.

                    Terminal Success

                    A tool can override isTerminalSuccess() to signal that its result represents
                    genuine completion of the whole task, such as a pull request opening
                    successfully. When that happens, the engine forces one final text-only answer
                    instead of offering another round of tool calls.

                    Tool Construction Context

                    Some tools need information at construction time that isn't a per-call
                    concern, such as which knowledge source is bound to the current agent
                    definition. That information is threaded through a small context object
                    passed to every tool factory, most of which simply ignore it.
                    """),
            new FixtureDocument("hybrid-retrieval.md", """
                    Why Hybrid Search

                    Vector similarity search finds semantically related passages even when the
                    exact wording differs, but it can miss queries that hinge on an exact term,
                    like a specific error code or function name. Full-text search is the reverse:
                    precise on exact terms, blind to paraphrase. Combining both signals covers
                    more real queries than either alone.

                    Normalizing Two Different Scales

                    Cosine distance and a text search rank score live on completely different,
                    incomparable numeric scales. Before they can be combined into one ranking,
                    each signal is normalized to a zero-to-one range within its own candidate set,
                    and only then combined with a configurable weight per signal.
                    """));

    private static final List<EvalCase> EVAL_CASES = List.of(
            new EvalCase("How does the app prevent one tenant's connection from leaking session state to another tenant?",
                    "tenant-isolation.md", "set_config"),
            new EvalCase("Why does the RLS setup use both ENABLE and FORCE row level security?",
                    "tenant-isolation.md", "table owner"),
            new EvalCase("Was an aspect-based approach ever tried for setting the tenant session variable?",
                    "tenant-isolation.md", "aspect"),
            new EvalCase("What encryption algorithm protects vendor API tokens at rest?",
                    "credential-encryption.md", "AES-256-GCM"),
            new EvalCase("Is there a tenant-wide shared LLM credential as a fallback?",
                    "credential-encryption.md", "fallback"),
            new EvalCase("When is a vendor credential's plaintext token actually reconstructed in memory?",
                    "credential-encryption.md", "LLM client"),
            new EvalCase("Why do tools implement a custom interface instead of a framework annotation?",
                    "tool-calling-contract.md", "ToolCallingChatEngine"),
            new EvalCase("What happens when a tool call is a terminal success?",
                    "tool-calling-contract.md", "final answer"),
            new EvalCase("Why combine vector search with full-text search instead of using just one?",
                    "hybrid-retrieval.md", "paraphrase"),
            new EvalCase("How are two different scoring scales made comparable before combining them?",
                    "hybrid-retrieval.md", "normalized"));

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void evaluateRetrievalPrecisionAtK() {
        EmbeddingModel embeddingModel = new EmbeddingModelFactory().create(LlmProvider.OPENAI, System.getenv("OPENAI_API_KEY"));
        ParagraphChunker chunker = new ParagraphChunker(400, 60);
        HybridScoreMerger merger = new HybridScoreMerger();

        // chunkId -> (documentName, content, embedding)
        record IndexedChunk(String documentName, String content, float[] embedding) {
        }
        Map<UUID, IndexedChunk> index = new LinkedHashMap<>();
        for (FixtureDocument document : FIXTURE_DOCUMENTS) {
            for (ParagraphChunker.Chunk chunk : chunker.chunk(document.text())) {
                Embedding embedding = embeddingModel.embed(chunk.text()).content();
                index.put(UUID.randomUUID(), new IndexedChunk(document.name(), chunk.text(), embedding.vector()));
            }
        }
        System.out.println("Indexed " + index.size() + " chunks across " + FIXTURE_DOCUMENTS.size() + " fixture documents.");

        int hits = 0;
        for (EvalCase evalCase : EVAL_CASES) {
            float[] queryEmbedding = embeddingModel.embed(evalCase.query()).content().vector();

            List<HybridScoreMerger.Candidate> vectorCandidates = index.entrySet().stream()
                    .map(e -> new HybridScoreMerger.Candidate(e.getKey(), 1.0 - cosineSimilarity(queryEmbedding, e.getValue().embedding())))
                    .toList();
            List<HybridScoreMerger.Candidate> textCandidates = index.entrySet().stream()
                    .map(e -> new HybridScoreMerger.Candidate(e.getKey(), keywordOverlapScore(evalCase.query(), e.getValue().content())))
                    .filter(c -> c.rawScore() > 0)
                    .toList();

            List<HybridScoreMerger.ScoredChunk> ranked = merger.merge(vectorCandidates, textCandidates, TOP_K);
            boolean hit = ranked.stream().anyMatch(scored -> {
                IndexedChunk chunk = index.get(scored.chunkId());
                return chunk.documentName().equals(evalCase.expectedDocument())
                        && chunk.content().toLowerCase(Locale.ROOT).contains(evalCase.expectedContentMarker().toLowerCase(Locale.ROOT));
            });
            if (hit) {
                hits++;
            }
            System.out.printf("[%s] \"%s\" -> expected %s (marker: \"%s\")%n",
                    hit ? "HIT " : "MISS", evalCase.query(), evalCase.expectedDocument(), evalCase.expectedContentMarker());
        }

        double precisionAtK = (double) hits / EVAL_CASES.size();
        System.out.printf("%nprecision@%d = %d/%d = %.2f%n", TOP_K, hits, EVAL_CASES.size(), precisionAtK);

        assertThat(precisionAtK).isGreaterThan(0.0);
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Stands in for Postgres's ts_rank -- counts how many distinct query words (3+ letters) appear in the chunk, case-insensitively. */
    private static double keywordOverlapScore(String query, String content) {
        String lowerContent = content.toLowerCase(Locale.ROOT);
        List<String> queryWords = List.of(query.toLowerCase(Locale.ROOT).split("\\W+")).stream()
                .filter(w -> w.length() >= 3)
                .collect(Collectors.toList());
        long matches = queryWords.stream().filter(lowerContent::contains).count();
        return matches;
    }
}
