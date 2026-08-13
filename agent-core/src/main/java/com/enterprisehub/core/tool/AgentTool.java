package com.enterprisehub.core.tool;

import java.util.Map;
import java.util.Set;

/**
 * The seam real capabilities (filesystem, terminal, git -- coming in
 * agent-runtime, Weeks 6-8) will plug into. Deliberately our own interface
 * rather than LangChain4j's reflection-based @Tool annotation: agent-core
 * stays the one place that knows about LangChain4j specifics
 * (ToolCallingChatEngine converts an AgentTool into a langchain4j
 * ToolSpecification), so agent-runtime's eventual tool implementations
 * don't need any LangChain4j import at all.
 *
 * Every parameter is a string -- no typed parameters, no nested objects.
 * That's enough for agent-runtime's real tools (a file path, a shell
 * command, a branch name) since those are all naturally string-shaped.
 * A parameter is required by default (the model must supply it) unless
 * its name is also returned by optionalParameterNames() -- see that
 * method's javadoc for what "optional" means for an implementation.
 */
public interface AgentTool {

    String name();

    String description();

    /** parameter name -> human-readable description, shown to the LLM. */
    Map<String, String> parameterDescriptions();

    /**
     * Names (a subset of parameterDescriptions().keySet()) the model may
     * omit entirely rather than being forced to supply some value just to
     * satisfy a required argument. Empty by default -- every existing tool
     * before this method existed keeps its current "everything required"
     * behavior with no change needed. An implementation whose parameter is
     * listed here must treat that key being ABSENT from execute()'s
     * `arguments` map as "not supplied" (arguments.get(name) returning null
     * is the only signal -- there is no separate "was it present" check).
     */
    default Set<String> optionalParameterNames() {
        return Set.of();
    }

    /**
     * Arguments are always non-null when present; the LLM decided to call
     * this tool with these values. A key listed in optionalParameterNames()
     * may be absent from this map entirely -- every other key is guaranteed
     * present. context.tenantId() is how a sandboxed implementation knows
     * which tenant's credentials to inject and which repo it's allowed to
     * touch -- never trust anything in `arguments` for that, since those
     * values came from the LLM, not from authenticated request state.
     */
    String execute(ToolExecutionContext context, Map<String, String> arguments);
}
