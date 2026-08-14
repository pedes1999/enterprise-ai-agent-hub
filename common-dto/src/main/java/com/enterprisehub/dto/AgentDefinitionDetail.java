package com.enterprisehub.dto;

import java.util.List;

/** GET /agents/definitions/{slug} -- the full, read-only configuration behind a catalog card's "view configuration" action. */
public record AgentDefinitionDetail(
        String slug,
        String name,
        String description,
        String systemPrompt,
        List<String> toolNames,
        String inputSourceType,
        List<String> requiredInputs,
        /** Null means this definition uses the tenant's/server's default model -- see AgentDefinition.preferredModelName. */
        String preferredModelName
) {
}
