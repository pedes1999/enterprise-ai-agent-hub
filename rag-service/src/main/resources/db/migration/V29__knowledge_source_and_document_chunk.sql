-- V29__knowledge_source_and_document_chunk.sql
--
-- The RAG data model: a tenant's knowledge_source (one uploaded/ingested
-- corpus, e.g. "Internal API docs") holds many document_chunk rows (the
-- paragraph-aware chunks ParagraphChunker produces, each with its own
-- embedding). Same RLS shape as every other tenant-scoped table (see
-- V4__tool_credentials.sql): ENABLE + FORCE together, one
-- tenant_isolation_<table> policy keyed off current_setting('app.current_tenant_id').
--
-- embedding is a FIXED vector(768) regardless of which vendor actually
-- produced it. Confirmed with the user: embeddings come from the tenant's
-- own OpenAI or Gemini credential (never a platform-wide key), and those two
-- vendors' embedding models don't share a native output size (OpenAI
-- text-embedding-3-small defaults to 1536, Gemini's embedding-001 is 768) --
-- a single pgvector column needs one fixed dimension no matter which
-- provider wrote the row, or cosine-distance comparisons across chunks
-- ingested under different tenant settings would be comparing vectors of
-- different lengths. EmbeddingModelFactory (agent-core) requests 768
-- dimensions from OpenAI explicitly (text-embedding-3-small supports a
-- `dimensions` truncation parameter) to match Gemini's native size, so this
-- column works for either vendor without per-provider schema variants.
CREATE TABLE knowledge_source (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(200) NOT NULL,
    source_type     VARCHAR(20) NOT NULL,   -- upload | url | repo (only 'upload' is implemented today)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE knowledge_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_source FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_knowledge_source ON knowledge_source
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE INDEX idx_knowledge_source_tenant ON knowledge_source(tenant_id);

CREATE TABLE document_chunk (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    knowledge_source_id     UUID NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
    document_name           VARCHAR(500) NOT NULL,
    content                 TEXT NOT NULL,
    embedding               vector(768) NOT NULL,
    metadata                JSONB NOT NULL DEFAULT '{}',
    chunk_index             INT NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE document_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_chunk FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_document_chunk ON document_chunk
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE INDEX idx_document_chunk_tenant ON document_chunk(tenant_id);
CREATE INDEX idx_document_chunk_source ON document_chunk(knowledge_source_id);

-- HNSW over IVFFlat: no training/list-count tuning needed for a corpus this
-- small, and HNSW's recall stays high without the "needs enough rows before
-- the index is any good" caveat IVFFlat has. vector_cosine_ops matches the
-- `<=>` cosine-distance operator DocumentChunkRepository's native query uses.
CREATE INDEX idx_document_chunk_embedding_hnsw ON document_chunk
    USING hnsw (embedding vector_cosine_ops);

-- 'english' is a constant regconfig, so to_tsvector(...) here is IMMUTABLE
-- and usable in a plain functional index -- no generated column needed.
CREATE INDEX idx_document_chunk_content_fts ON document_chunk
    USING gin (to_tsvector('english', content));
