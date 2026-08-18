package com.enterprisehub.rag.retrieval;

/** One retrieval hit, with everything needed for a citation: the chunk text itself, which document it came from, and its combined hybrid score (higher is more relevant). */
public record RetrievedChunk(String chunkId, String documentName, String content, double score) {
}
