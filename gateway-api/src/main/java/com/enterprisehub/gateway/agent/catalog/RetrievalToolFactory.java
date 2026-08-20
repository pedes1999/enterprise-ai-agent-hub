package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.rag.repository.AgentKnowledgeSourceBindingRepository;
import com.enterprisehub.rag.retrieval.RetrievalQueryService;
import com.enterprisehub.rag.tool.RetrievalTool;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The only ToolFactory that actually reads ToolCreationContext -- looks up
 * whether this (tenant, agent definition) pair has a knowledge source bound
 * (see V30__agent_knowledge_source_binding.sql) once, at construction time,
 * so RetrievalTool itself never has to touch the database or know about
 * AgentDefinition at all.
 */
@Component
public class RetrievalToolFactory implements ToolFactory {

    private final AgentKnowledgeSourceBindingRepository bindingRepository;
    private final RetrievalQueryService retrievalQueryService;

    public RetrievalToolFactory(AgentKnowledgeSourceBindingRepository bindingRepository, RetrievalQueryService retrievalQueryService) {
        this.bindingRepository = bindingRepository;
        this.retrievalQueryService = retrievalQueryService;
    }

    @Override
    public String toolName() {
        return "retrieval";
    }

    @Override
    public String category() {
        return "knowledge";
    }

    @Override
    public AgentTool create(SandboxSession session, CredentialResolver credentialResolver, ToolCreationContext toolContext) {
        Optional<UUID> knowledgeSourceId = toolContext.agentDefinitionId() == null
                ? Optional.empty()
                : bindingRepository.findByTenantIdAndAgentDefinitionId(UUID.fromString(toolContext.tenantId()), toolContext.agentDefinitionId())
                        .map(binding -> binding.getKnowledgeSourceId());
        return new RetrievalTool(knowledgeSourceId, toolContext.userId(), retrievalQueryService);
    }
}
