package com.enterprisehub.gateway.rag;

import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.rag.entity.AgentKnowledgeSourceBinding;
import com.enterprisehub.rag.entity.KnowledgeSource;
import com.enterprisehub.rag.repository.AgentKnowledgeSourceBindingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * How a tenant admin attaches a knowledge source to an AgentDefinition "via
 * config, without writing new code" -- see
 * V30__agent_knowledge_source_binding.sql's javadoc for why this is a
 * tenant-scoped join row rather than a column on the shared agent_definitions
 * table.
 */
@Service
public class AgentKnowledgeSourceBindingService {

    private final AgentKnowledgeSourceBindingRepository bindingRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final KnowledgeSourceService knowledgeSourceService;

    public AgentKnowledgeSourceBindingService(AgentKnowledgeSourceBindingRepository bindingRepository,
                                               AgentDefinitionRepository agentDefinitionRepository,
                                               KnowledgeSourceService knowledgeSourceService) {
        this.bindingRepository = bindingRepository;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.knowledgeSourceService = knowledgeSourceService;
    }

    /** Upsert -- attaching a different source to the same agent replaces the previous binding (see the table's UNIQUE (tenant_id, agent_definition_id)). */
    public void attach(UUID tenantId, UUID knowledgeSourceId, String agentSlug) {
        KnowledgeSource source = knowledgeSourceService.getOwned(tenantId, knowledgeSourceId);
        AgentDefinition definition = resolveAgentDefinition(agentSlug);

        AgentKnowledgeSourceBinding binding = bindingRepository.findByTenantIdAndAgentDefinitionId(tenantId, definition.getId())
                .orElseGet(AgentKnowledgeSourceBinding::new);
        binding.setTenantId(tenantId);
        binding.setAgentDefinitionId(definition.getId());
        binding.setKnowledgeSourceId(source.getId());
        bindingRepository.save(binding);
    }

    public void detach(UUID tenantId, String agentSlug) {
        AgentDefinition definition = resolveAgentDefinition(agentSlug);
        bindingRepository.deleteByTenantIdAndAgentDefinitionId(tenantId, definition.getId());
    }

    private AgentDefinition resolveAgentDefinition(String agentSlug) {
        return agentDefinitionRepository.findBySlugAndActiveTrue(agentSlug)
                .orElseThrow(() -> new RagException(HttpStatus.BAD_REQUEST, "Unknown or inactive agent: " + agentSlug));
    }
}
