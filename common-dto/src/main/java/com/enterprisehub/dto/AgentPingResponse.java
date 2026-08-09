package com.enterprisehub.dto;

public record AgentPingResponse(
        String provider,
        String modelName,
        String reply
) {
}
