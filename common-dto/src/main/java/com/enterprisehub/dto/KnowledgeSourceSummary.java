package com.enterprisehub.dto;

import java.time.Instant;

public record KnowledgeSourceSummary(String id, String name, String sourceType, Instant createdAt) {
}
