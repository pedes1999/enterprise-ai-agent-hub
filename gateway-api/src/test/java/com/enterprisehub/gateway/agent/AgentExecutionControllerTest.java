package com.enterprisehub.gateway.agent;

import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.error.GlobalExceptionHandler;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        executionService = mock(AgentExecutionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentExecutionController(executionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        PlatformPrincipal principal = new PlatformPrincipal("dev-1", tenantId.toString(), "DEVELOPER");
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
        when(executionService.enqueue(eq(tenantId), eq("list files"))).thenReturn(queued);

        mockMvc.perform(post("/agents/execute")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"list files"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.executionId").value(queued.getId().toString()));
    }

    @Test
    void execute_blankPrompt_returns400_neverEnqueues() throws Exception {
        mockMvc.perform(post("/agents/execute")
                        .contentType("application/json")
                        .content("""
                                {"prompt":" "}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(executionService);
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
}
