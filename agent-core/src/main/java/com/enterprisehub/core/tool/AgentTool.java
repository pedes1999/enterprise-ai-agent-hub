package com.enterprisehub.core.tool;

import java.util.Map;

/**
 * The seam real capabilities (filesystem, terminal, git -- coming in
 * agent-runtime, Weeks 6-8) will plug into. Deliberately our own interface
 * rather than LangChain4j's reflection-based @Tool annotation: agent-core
 * stays the one place that knows about LangChain4j specifics
 * (ToolCallingChatEngine converts an AgentTool into a langchain4j
 * ToolSpecification), so agent-runtime's eventual tool implementations
 * don't need any LangChain4j import at all.
 *
 * Every parameter is treated as a required string for now -- no typed/
 * optional parameters, no nested objects. That's enough to prove the
 * tool-calling loop works end to end; agent-runtime's real tools (a file
 * path, a shell command) are naturally string-shaped anyway.
 */
public interface AgentTool {

    String name();

    String description();

    /** parameter name -> human-readable description, shown to the LLM. */
    Map<String, String> parameterDescriptions();

    /**
     * Arguments are always present and non-null; the LLM decided to call this
     * tool with these values. context.tenantId() is how a sandboxed
     * implementation knows which tenant's credentials to inject and which
     * repo it's allowed to touch -- never trust anything in `arguments` for
     * that, since those values came from the LLM, not from authenticated
     * request state.
     */
    String execute(ToolExecutionContext context, Map<String, String> arguments);
}
