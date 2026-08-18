package com.enterprisehub.rag.repository;

import com.enterprisehub.rag.entity.AgentKnowledgeSourceBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgentKnowledgeSourceBindingRepository extends JpaRepository<AgentKnowledgeSourceBinding, UUID> {

    /** Read at tool-construction time by RetrievalToolFactory -- see ToolCreationContext. */
    Optional<AgentKnowledgeSourceBinding> findByTenantIdAndAgentDefinitionId(UUID tenantId, UUID agentDefinitionId);

    void deleteByTenantIdAndAgentDefinitionId(UUID tenantId, UUID agentDefinitionId);
}
