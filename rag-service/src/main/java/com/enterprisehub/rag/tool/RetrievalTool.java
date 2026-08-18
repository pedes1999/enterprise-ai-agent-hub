package com.enterprisehub.rag.tool;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.rag.retrieval.RetrievalQueryService;
import com.enterprisehub.rag.retrieval.RetrievedChunk;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * No sandbox needed (a DB query, not a filesystem/shell/git operation) --
 * same shape as CurrentDateTimeTool. Which knowledge source (if any) this
 * agent execution can search is resolved once, at construction time, by
 * RetrievalToolFactory reading the tenant's AgentKnowledgeSourceBinding --
 * the model only ever supplies `query`, never a source id, so there is
 * nothing for it to get wrong or use to reach another tenant's/agent's
 * source.
 *
 * A tenant that hasn't attached a knowledge source to this agent gets a
 * plain, non-error explanation instead of a failure -- every tenant using
 * ticket-resolver has `retrieval` in tool_names now (see
 * V31__ticket_resolver_retrieval_tool.sql), most without RAG set up at all,
 * and a tool call returning "nothing to search" is a normal outcome the
 * model can just move past, not a broken execution.
 */
public class RetrievalTool implements AgentTool {

    private static final int TOP_K = 5;
    private static final String NO_SOURCE_MESSAGE =
            "No knowledge source is attached to this agent. A tenant admin can attach one via "
                    + "PUT /knowledge-sources/{id}/agent-bindings/{agentSlug}. Proceed without retrieved context.";

    private final Optional<UUID> knowledgeSourceId;
    private final String userId;
    private final RetrievalQueryService retrievalQueryService;

    public RetrievalTool(Optional<UUID> knowledgeSourceId, String userId, RetrievalQueryService retrievalQueryService) {
        this.knowledgeSourceId = knowledgeSourceId;
        this.userId = userId;
        this.retrievalQueryService = retrievalQueryService;
    }

    @Override
    public String name() {
        return "retrieval";
    }

    @Override
    public String description() {
        return "Searches this agent's attached knowledge source (if any) for content relevant to a query, "
                + "combining semantic and keyword search. Returns the most relevant passages with their source "
                + "document names, for use as supporting context -- not as ground truth to follow blindly.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of("query", "What to search for, in plain language (e.g. 'error handling conventions for REST endpoints').");
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        if (knowledgeSourceId.isEmpty()) {
            return NO_SOURCE_MESSAGE;
        }
        String query = arguments.get("query");
        List<RetrievedChunk> results = retrievalQueryService.query(
                context.tenantId(), userId, knowledgeSourceId.get(), query, TOP_K);
        if (results.isEmpty()) {
            return "No relevant content found in the attached knowledge source for: " + query;
        }
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RetrievedChunk chunk = results.get(i);
            formatted.append("[").append(i + 1).append("] from \"").append(chunk.documentName())
                    .append("\" (relevance ").append(String.format("%.2f", chunk.score())).append("):\n")
                    .append(chunk.content()).append("\n\n");
        }
        return formatted.toString().strip();
    }
}
