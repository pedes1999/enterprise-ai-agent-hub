package com.enterprisehub.dto;

public record IngestDocumentResponse(String knowledgeSourceId, String documentName, int chunkCount) {
}
