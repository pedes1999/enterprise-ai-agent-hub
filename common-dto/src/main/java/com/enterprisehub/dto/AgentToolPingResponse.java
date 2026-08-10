package com.enterprisehub.dto;

public record AgentToolPingResponse(
        String provider,
        String modelName,
        String reply,
        boolean toolWasUsed,
        String agentSlug
) {
}
