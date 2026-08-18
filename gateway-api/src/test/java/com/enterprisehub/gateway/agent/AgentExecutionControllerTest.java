package com.enterprisehub.gateway.agent;

import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.error.GlobalExceptionHandler;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentExecutionControllerTest {

    private AgentExecutionService executionService;
    private AgentDefinitionService agentDefinitionService;
    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        executionService = mock(AgentExecutionService.class);
        agentDefinitionService = mock(AgentDefinitionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentExecutionController(executionService, agentDefinitionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver(), new PageableHandlerMethodArgumentResolver())
                .build();

        PlatformPrincipal principal = new PlatformPrincipal(userId.toString(), tenantId.toString(), "DEVELOPER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void execute_returns202WithQueuedIdImmediately() throws Exception {
        AgentExecution queued = new AgentExecution();
        queued.setId(UUID.randomUUID());
        queued.setStatus("QUEUED");
        when(executionService.enqueue(EnqueueExecutionCommand.forAgent(tenantId, AgentPromptRunner.DEFAULT_AGENT_SLUG)
                .prompt("list files")
                .triggeredBy(userId)
                .build()))
                .thenReturn(queued);

        mockMvc.perform(post("/agents/execute")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"list files"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.executionId").value(queued.getId().toString()));
    }

    @Test
    void execute_explicitAgentSlug_passedThrough() throws Exception {
        AgentExecution queued = new AgentExecution();
        queued.setId(UUID.randomUUID());
        queued.setStatus("QUEUED");
        when(executionService.enqueue(EnqueueExecutionCommand.forAgent(tenantId, "coding-agent")
                .prompt("build a feature")
                .triggeredBy(userId)
                .build()))
                .thenReturn(queued);

        mockMvc.perform(post("/agents/execute")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"build a feature","agentSlug":"coding-agent"}"""))
                .andExpect(status().isAccepted());
    }

    @Test
    void execute_repositoryUrlAndInputParameters_passedThrough() throws Exception {
        AgentExecution queued = new AgentExecution();
        queued.setId(UUID.randomUUID());
        queued.setStatus("QUEUED");
        when(executionService.enqueue(EnqueueExecutionCommand.forAgent(tenantId, "coding-agent")
                .prompt("also check the auth module")
                .repositoryUrl("https://github.com/org/repo.git")
                .inputParameters(java.util.Map.of("text", "Ticket: fix the bug"))
                .triggeredBy(userId)
                .build()))
                .thenReturn(queued);

        mockMvc.perform(post("/agents/execute")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"also check the auth module","agentSlug":"coding-agent",
                                 "repositoryUrl":"https://github.com/org/repo.git",
                                 "inputParameters":{"text":"Ticket: fix the bug"}}"""))
                .andExpect(status().isAccepted());
    }

    @Test
    void execute_missingRequiredInput_returns400() throws Exception {
        // No ad hoc "prompt is required" check in the controller anymore --
        // this is now entirely AgentExecutionService.enqueue()'s call
        // (see AgentExecutionServiceTest for the per-AgentDefinition
        // required-inputs coverage); the controller's job is just to map
        // whatever AgentException it throws to the right status code.
        when(executionService.enqueue(EnqueueExecutionCommand.forAgent(tenantId, AgentPromptRunner.DEFAULT_AGENT_SLUG)
                .prompt(" ")
                .triggeredBy(userId)
                .build()))
                .thenThrow(new AgentException(org.springframework.http.HttpStatus.BAD_REQUEST, "Missing required input(s): prompt"));

        mockMvc.perform(post("/agents/execute")
                        .contentType("application/json")
                        .content("""
                                {"prompt":" "}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void execute_tenantAtConcurrencyLimit_returns429() throws Exception {
        when(executionService.enqueue(EnqueueExecutionCommand.forAgent(tenantId, AgentPromptRunner.DEFAULT_AGENT_SLUG)
                .prompt("list files")
                .triggeredBy(userId)
                .build()))
                .thenThrow(new AgentException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "This tenant already has 5 agent executions in progress (limit 5) -- wait for one to finish before starting another."));

        mockMvc.perform(post("/agents/execute")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"list files"}"""))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void getUsage_returnsActiveAndLimit() throws Exception {
        when(executionService.getUsage(tenantId)).thenReturn(new com.enterprisehub.dto.ExecutionUsage(3L, 5));

        mockMvc.perform(get("/agents/executions/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(3))
                .andExpect(jsonPath("$.limit").value(5));
    }

    @Test
    void getUsage_routeIsNotShadowedByExecutionIdPathVariable() throws Exception {
        // Regression guard: "usage" must never be captured as {id} on
        // getExecution() instead of routing to getUsage().
        when(executionService.getUsage(tenantId)).thenReturn(new com.enterprisehub.dto.ExecutionUsage(0L, 5));

        mockMvc.perform(get("/agents/executions/usage"))
                .andExpect(status().isOk());

        verify(executionService, never()).findForTenant(any(), any());
    }

    @Test
    void getTokenUsageStats_returnsAggregateStats() throws Exception {
        when(executionService.getTokenUsageStats(tenantId, "coding-agent"))
                .thenReturn(new com.enterprisehub.dto.AgentTokenUsageStats("coding-agent", 8L, 15_000, 27_500.5, 42_000));

        mockMvc.perform(get("/agents/executions/token-usage-stats").param("agentSlug", "coding-agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(8))
                .andExpect(jsonPath("$.minTokens").value(15000))
                .andExpect(jsonPath("$.maxTokens").value(42000));
    }

    @Test
    void getTokenUsageStats_routeIsNotShadowedByExecutionIdPathVariable() throws Exception {
        when(executionService.getTokenUsageStats(tenantId, "coding-agent"))
                .thenReturn(new com.enterprisehub.dto.AgentTokenUsageStats("coding-agent", 0L, null, null, null));

        mockMvc.perform(get("/agents/executions/token-usage-stats").param("agentSlug", "coding-agent"))
                .andExpect(status().isOk());

        verify(executionService, never()).findForTenant(any(), any());
    }

    @Test
    void getExecution_found_returnsFullStatus() throws Exception {
        UUID id = UUID.randomUUID();
        AgentExecution execution = new AgentExecution();
        execution.setId(id);
        execution.setTenantId(tenantId);
        execution.setStatus("SUCCEEDED");
        execution.setPrompt("list files");
        execution.setReply("a.txt, b.txt");
        execution.setToolWasUsed(true);
        execution.setCreatedAt(Instant.now());
        when(executionService.findForTenant(tenantId, id)).thenReturn(Optional.of(execution));

        mockMvc.perform(get("/agents/executions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.reply").value("a.txt, b.txt"))
                .andExpect(jsonPath("$.toolWasUsed").value(true));
    }

    @Test
    void getExecution_notFoundOrBelongsToAnotherTenant_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(executionService.findForTenant(eq(tenantId), eq(id))).thenReturn(Optional.empty());

        mockMvc.perform(get("/agents/executions/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getToolExecutions_returnsOrderedTrace() throws Exception {
        UUID id = UUID.randomUUID();
        when(executionService.getToolExecutions(tenantId, id)).thenReturn(List.of(
                new com.enterprisehub.dto.ToolExecutionRecord("git_clone", 120L, "SUCCESS", null, Instant.now())));

        mockMvc.perform(get("/agents/executions/" + id + "/tool-executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toolName").value("git_clone"))
                .andExpect(jsonPath("$[0].outcome").value("SUCCESS"));
    }

    @Test
    void getToolExecutions_unknownExecution_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(executionService.getToolExecutions(tenantId, id))
                .thenThrow(new AgentException(HttpStatus.NOT_FOUND, "No execution with id " + id));

        mockMvc.perform(get("/agents/executions/" + id + "/tool-executions"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listExecutions_returnsPagedTenantScopedResults() throws Exception {
        AgentExecution execution = new AgentExecution();
        execution.setId(UUID.randomUUID());
        execution.setStatus("RUNNING");
        execution.setPrompt("do something");
        when(executionService.list(eq(tenantId), eq(null), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(execution)));

        mockMvc.perform(get("/agents/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void listExecutions_statusFilter_passedThrough() throws Exception {
        when(executionService.list(eq(tenantId), eq("FAILED"), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/agents/executions").param("status", "FAILED"))
                .andExpect(status().isOk());

        verify(executionService).list(eq(tenantId), eq("FAILED"), any());
    }

    @Test
    void listDefinitions_returnsCatalogFromService() throws Exception {
        when(agentDefinitionService.listActive()).thenReturn(List.of(
                new com.enterprisehub.dto.AgentDefinitionSummary("coding-agent", "Coding Agent", "desc",
                        List.of("git_clone", "read_file", "write_file", "run_shell_command"))));

        mockMvc.perform(get("/agents/definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("coding-agent"))
                .andExpect(jsonPath("$[0].toolNames[0]").value("git_clone"));
    }

    @Test
    void getDefinition_knownSlug_returnsFullConfiguration() throws Exception {
        when(agentDefinitionService.getDetail("coding-agent")).thenReturn(
                new com.enterprisehub.dto.AgentDefinitionDetail("coding-agent", "Coding Agent", "desc",
                        "You are a coding agent.", List.of("git_clone", "open_pull_request"), null, List.of("repositoryUrl"), null));

        mockMvc.perform(get("/agents/definitions/coding-agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemPrompt").value("You are a coding agent."))
                .andExpect(jsonPath("$.requiredInputs[0]").value("repositoryUrl"));
    }

    @Test
    void getDefinition_unknownSlug_returns404() throws Exception {
        when(agentDefinitionService.getDetail("does-not-exist"))
                .thenThrow(new AgentException(HttpStatus.NOT_FOUND, "Unknown or inactive agent: does-not-exist"));

        mockMvc.perform(get("/agents/definitions/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
