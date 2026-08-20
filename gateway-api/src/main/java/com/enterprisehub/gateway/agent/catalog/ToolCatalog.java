package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.gateway.agent.AgentException;
import com.enterprisehub.runtime.audit.AuditingTool;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every tool available to be referenced by an AgentDefinition's tool_names
 * list, self-assembled from every ToolFactory bean Spring finds -- this
 * class never lists tools by name itself, so adding tool #101 never
 * requires touching it. The actual registry a "library of hundreds of
 * tools" is built on.
 */
@Component
public class ToolCatalog {

    private final Map<String, ToolFactory> factoriesByName;

    public ToolCatalog(List<ToolFactory> factories) {
        this.factoriesByName = factories.stream()
                .collect(Collectors.toMap(ToolFactory::toolName, Function.identity()));
    }

    /**
     * Builds live AgentTool instances for the given names, all wired to
     * the SAME session -- see SandboxSession's javadoc for why that's what
     * lets them share state within one execution. Throws if an
     * AgentDefinition references a tool name that doesn't exist in the
     * catalog (a data-integrity problem with the definition itself, not a
     * caller error -- see AgentPromptRunner).
     *
     * Every tool is wrapped in AuditingTool on the way out, which is what
     * makes "every tool call is audited" true by construction rather than
     * by each tool remembering to do it -- see AuditingTool's javadoc for
     * the three tools that silently weren't.
     */
    public List<AgentTool> instantiate(List<String> toolNames, SandboxSession session,
                                        ToolExecutionListener listener, CredentialResolver credentialResolver,
                                        ToolCreationContext toolContext) {
        return toolNames.stream()
                .map(name -> factoryFor(name).create(session, credentialResolver, toolContext))
                .map(tool -> (AgentTool) new AuditingTool(tool, listener))
                .toList();
    }

    public List<ToolFactory> all() {
        return List.copyOf(factoriesByName.values());
    }

    private ToolFactory factoryFor(String toolName) {
        ToolFactory factory = factoriesByName.get(toolName);
        if (factory == null) {
            throw new AgentException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Agent definition references unknown tool '" + toolName + "' -- catalog/definition are out of sync");
        }
        return factory;
    }
}
