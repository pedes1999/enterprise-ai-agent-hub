package com.enterprisehub.gateway.rag;

import com.enterprisehub.dto.AgentKnowledgeSourceBindingSummary;
import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import com.enterprisehub.rag.entity.AgentKnowledgeSourceBinding;
import com.enterprisehub.rag.entity.KnowledgeSource;
import com.enterprisehub.rag.repository.AgentKnowledgeSourceBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentKnowledgeSourceBindingServiceTest {

    private AgentKnowledgeSourceBindingRepository bindingRepository;
    private AgentDefinitionRepository agentDefinitionRepository;
    private KnowledgeSourceService knowledgeSourceService;
    private AgentKnowledgeSourceBindingService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID agentDefinitionId = UUID.randomUUID();
    private final UUID knowledgeSourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bindingRepository = mock(AgentKnowledgeSourceBindingRepository.class);
        agentDefinitionRepository = mock(AgentDefinitionRepository.class);
        knowledgeSourceService = mock(KnowledgeSourceService.class);
        service = new AgentKnowledgeSourceBindingService(bindingRepository, agentDefinitionRepository, knowledgeSourceService);
    }

    @Test
    void findForAgent_noBinding_returnsEmpty() {
        AgentDefinition definition = definitionWithId();
        when(agentDefinitionRepository.findBySlugAndActiveTrue("ticket-resolver")).thenReturn(Optional.of(definition));
        when(bindingRepository.findByTenantIdAndAgentDefinitionId(tenantId, agentDefinitionId)).thenReturn(Optional.empty());

        assertThat(service.findForAgent(tenantId, "ticket-resolver")).isEmpty();
    }

    @Test
    void findForAgent_bound_returnsSourceIdAndName() {
        AgentDefinition definition = definitionWithId();
        when(agentDefinitionRepository.findBySlugAndActiveTrue("ticket-resolver")).thenReturn(Optional.of(definition));

        AgentKnowledgeSourceBinding binding = new AgentKnowledgeSourceBinding();
        binding.setKnowledgeSourceId(knowledgeSourceId);
        when(bindingRepository.findByTenantIdAndAgentDefinitionId(tenantId, agentDefinitionId)).thenReturn(Optional.of(binding));

        KnowledgeSource source = new KnowledgeSource();
        source.setId(knowledgeSourceId);
        source.setName("Internal API docs");
        when(knowledgeSourceService.getOwned(tenantId, knowledgeSourceId)).thenReturn(source);

        Optional<AgentKnowledgeSourceBindingSummary> result = service.findForAgent(tenantId, "ticket-resolver");

        assertThat(result).contains(new AgentKnowledgeSourceBindingSummary(knowledgeSourceId.toString(), "Internal API docs"));
    }

    @Test
    void findForAgent_unknownAgentSlug_throwsBadRequest() {
        when(agentDefinitionRepository.findBySlugAndActiveTrue("does-not-exist")).thenReturn(Optional.empty());

        RagException thrown = (RagException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service.findForAgent(tenantId, "does-not-exist"));

        assertThat(thrown).hasMessageContaining("does-not-exist");
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private AgentDefinition definitionWithId() {
        AgentDefinition definition = new AgentDefinition();
        definition.setId(agentDefinitionId);
        return definition;
    }
}
