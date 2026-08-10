package com.enterprisehub.dto;

import java.util.List;

/** GET /agents/definitions -- the browsable catalog a caller picks an agentSlug from. */
public record AgentDefinitionSummary(
        String slug,
        String name,
        String description,
        List<String> toolNames
) {
}
