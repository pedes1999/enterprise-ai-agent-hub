package com.enterprisehub.gateway.credential;

import com.enterprisehub.runtime.credential.CredentialResolver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * gateway-api's implementation of agent-runtime's CredentialResolver SPI.
 * Maps a resolved tool_credentials value onto the environment-variable name
 * a sandboxed tool actually expects to find it under (e.g. GitCloneTool
 * looks for GIT_TOKEN) -- that mapping is intentionally kept here, not in
 * agent-runtime, since it's a detail of how THIS resolver's storage
 * happens to be keyed, not a general property of "credentials."
 */
@Component
public class JpaCredentialResolver implements CredentialResolver {

    private final ToolCredentialService toolCredentialService;

    public JpaCredentialResolver(ToolCredentialService toolCredentialService) {
        this.toolCredentialService = toolCredentialService;
    }

    @Override
    public Map<String, String> resolve(String tenantId, String credentialKind) {
        return toolCredentialService.decryptActiveValue(UUID.fromString(tenantId), credentialKind)
                .map(value -> Map.of(envVarNameFor(credentialKind), value))
                .orElse(Map.of());
    }

    private String envVarNameFor(String credentialKind) {
        return switch (credentialKind) {
            case "GIT" -> "GIT_TOKEN";
            default -> credentialKind + "_TOKEN";
        };
    }
}
