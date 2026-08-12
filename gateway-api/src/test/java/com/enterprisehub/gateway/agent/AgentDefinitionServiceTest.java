package com.enterprisehub.gateway.agent;

import com.enterprisehub.dto.AgentDefinitionDetail;
import com.enterprisehub.dto.AgentDefinitionSummary;
import com.enterprisehub.gateway.entity.AgentDefinition;
import com.enterprisehub.gateway.repository.AgentDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDefinitionServiceTest {

    private AgentDefinitionRepository repository;
    private AgentDefinitionService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentDefinitionRepository.class);
        service = new AgentDefinitionService(repository);
    }

    private AgentDefinition codingAgent() {
        AgentDefinition definition = new AgentDefinition();
        definition.setSlug("coding-agent");
        definition.setName("Coding Agent");
        definition.setDescription("Clones a repo, edits it, opens a PR.");
        definition.setSystemPrompt("You are a coding agent.");
        definition.setToolNames(List.of("git_clone", "read_file", "write_file", "open_pull_request"));
        definition.setInputSourceType(null);
        definition.setRequiredInputs(List.of("repositoryUrl"));
        return definition;
    }

    @Test
    void listActive_mapsToSummaries_withoutSystemPromptOrRequiredInputs() {
        when(repository.findByActiveTrue()).thenReturn(List.of(codingAgent()));

        List<AgentDefinitionSummary> summaries = service.listActive();

        assertThat(summaries).hasSize(1);
        AgentDefinitionSummary summary = summaries.get(0);
        assertThat(summary.slug()).isEqualTo("coding-agent");
        assertThat(summary.name()).isEqualTo("Coding Agent");
        assertThat(summary.toolNames()).containsExactly("git_clone", "read_file", "write_file", "open_pull_request");
    }

    @Test
    void getDetail_knownSlug_returnsFullConfiguration() {
        when(repository.findBySlugAndActiveTrue("coding-agent")).thenReturn(Optional.of(codingAgent()));

        AgentDefinitionDetail detail = service.getDetail("coding-agent");

        assertThat(detail.slug()).isEqualTo("coding-agent");
        assertThat(detail.systemPrompt()).isEqualTo("You are a coding agent.");
        assertThat(detail.toolNames()).contains("open_pull_request");
        assertThat(detail.inputSourceType()).isNull();
        assertThat(detail.requiredInputs()).containsExactly("repositoryUrl");
    }

    @Test
    void getDetail_unknownSlug_throwsNotFound() {
        when(repository.findBySlugAndActiveTrue("does-not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail("does-not-exist"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does-not-exist")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
