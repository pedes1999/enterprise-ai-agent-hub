package com.enterprisehub.gateway.agent;

import com.enterprisehub.dto.AgentDefinitionDetail;
import com.enterprisehub.dto.AgentDefinitionSummary;
import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read-only browsing of the agent catalog -- see AgentDefinition's javadoc. No admin CRUD yet. */
@Service
public class AgentDefinitionService {

    private final AgentDefinitionRepository repository;

    public AgentDefinitionService(AgentDefinitionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AgentDefinitionSummary> listActive() {
        return repository.findByActiveTrue().stream()
                .map(this::toSummary)
                .toList();
    }

    /** Full, read-only configuration for one definition -- backs the catalog card's "view configuration" action. */
    @Transactional(readOnly = true)
    public AgentDefinitionDetail getDetail(String slug) {
        AgentDefinition definition = repository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new AgentException(HttpStatus.NOT_FOUND, "Unknown or inactive agent: " + slug));
        return toDetail(definition);
    }

    private AgentDefinitionSummary toSummary(AgentDefinition definition) {
        return new AgentDefinitionSummary(definition.getSlug(), definition.getName(), definition.getDescription(), definition.getToolNames());
    }

    private AgentDefinitionDetail toDetail(AgentDefinition definition) {
        return new AgentDefinitionDetail(
                definition.getSlug(), definition.getName(), definition.getDescription(), definition.getSystemPrompt(),
                definition.getToolNames(), definition.getInputSourceType(), definition.getRequiredInputs(),
                definition.getPreferredModelName());
    }
}
