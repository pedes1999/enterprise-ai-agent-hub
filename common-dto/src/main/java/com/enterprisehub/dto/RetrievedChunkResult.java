package com.enterprisehub.dto;

/** One retrieval hit -- content plus enough to cite it (documentName) and judge it (score, higher is more relevant). */
public record RetrievedChunkResult(String chunkId, String documentName, String content, double score) {
}
