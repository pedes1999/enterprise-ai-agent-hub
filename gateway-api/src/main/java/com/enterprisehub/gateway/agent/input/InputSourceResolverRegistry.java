package com.enterprisehub.gateway.agent.input;

import com.enterprisehub.gateway.agent.AgentException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Self-assembled from every InputSourceResolver bean Spring finds, exactly
 * the way ToolCatalog assembles ToolFactory beans -- adding resolver #2
 * (e.g. a future JiraTicketResolver) never requires touching this class.
 */
@Component
public class InputSourceResolverRegistry {

    private final Map<String, InputSourceResolver> resolversByType;

    public InputSourceResolverRegistry(List<InputSourceResolver> resolvers) {
        this.resolversByType = resolvers.stream()
                .collect(Collectors.toMap(InputSourceResolver::sourceType, Function.identity()));
    }

    /**
     * Throws if inputSourceType names a resolver that doesn't exist -- a
     * data-integrity problem with the AgentDefinition itself (same posture
     * as ToolCatalog.factoryFor() for an unknown tool name), not a caller
     * error.
     */
    public String resolve(String inputSourceType, UUID tenantId, Map<String, String> parameters) {
        InputSourceResolver resolver = resolversByType.get(inputSourceType);
        if (resolver == null) {
            throw new AgentException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Agent definition references unknown input source type '" + inputSourceType
                            + "' -- registry/definition are out of sync");
        }
        return resolver.resolve(tenantId, parameters);
    }
}
