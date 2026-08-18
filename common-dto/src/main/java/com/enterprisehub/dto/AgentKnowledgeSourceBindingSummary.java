package com.enterprisehub.dto;

/** What knowledge source (if any) is currently attached to a given AgentDefinition for the caller's tenant. */
public record AgentKnowledgeSourceBindingSummary(String knowledgeSourceId, String knowledgeSourceName) {
}
