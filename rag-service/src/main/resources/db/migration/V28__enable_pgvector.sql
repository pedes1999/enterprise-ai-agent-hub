-- V28__enable_pgvector.sql
--
-- Enables pgvector so document_chunk (V29) can store real embedding vectors
-- and use an approximate-nearest-neighbor index for similarity search. No
-- extension has been enabled anywhere in this schema before now (UUID
-- generation relies on Postgres's built-in gen_random_uuid(), no pgcrypto
-- needed) -- this is the first one.
CREATE EXTENSION IF NOT EXISTS vector;
