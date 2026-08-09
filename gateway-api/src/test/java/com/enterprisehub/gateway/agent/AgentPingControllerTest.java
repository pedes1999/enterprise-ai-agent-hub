package com.enterprisehub.gateway.agent;

import com.enterprisehub.dto.AgentPingResponse;
import com.enterprisehub.dto.AgentToolPingResponse;
import com.enterprisehub.gateway.error.GlobalExceptionHandler;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentPingControllerTest {

    private AgentPingService agentPingService;
    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentPingService = mock(AgentPingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentPingController(agentPingService))
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
    void ping_returns200WithReply() throws Exception {
        when(agentPingService.ping(eq(tenantId), eq("Hello"))).thenReturn(
                new AgentPingResponse("ANTHROPIC", "claude-3-5-sonnet-20240620", "Hi there!"));

        mockMvc.perform(post("/agents/ping")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"Hello"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Hi there!"))
                .andExpect(jsonPath("$.provider").value("ANTHROPIC"));
    }

    @Test
    void ping_noCredential_returns400() throws Exception {
        when(agentPingService.ping(any(), any())).thenThrow(
                new AgentException(HttpStatus.BAD_REQUEST, "No active ANTHROPIC credential configured for this tenant -- PUT /vendor-credentials first"));

        mockMvc.perform(post("/agents/ping")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"Hello"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ping_providerFailure_returns502() throws Exception {
        when(agentPingService.ping(any(), any())).thenThrow(
                new AgentException(HttpStatus.BAD_GATEWAY, "Anthropic API call failed: timeout"));

        mockMvc.perform(post("/agents/ping")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"Hello"}"""))
                .andExpect(status().isBadGateway());
    }

    @Test
    void pingWithTools_returns200WithReplyAndToolUsageFlag() throws Exception {
        when(agentPingService.pingWithTools(eq(tenantId), eq("What time is it?"))).thenReturn(
                new AgentToolPingResponse("ANTHROPIC", "claude-3-5-sonnet-20240620", "It is noon", true));

        mockMvc.perform(post("/agents/ping-with-tools")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"What time is it?"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("It is noon"))
                .andExpect(jsonPath("$.toolWasUsed").value(true));
    }

    @Test
    void pingWithTools_noCredential_returns400() throws Exception {
        when(agentPingService.pingWithTools(any(), any())).thenThrow(
                new AgentException(HttpStatus.BAD_REQUEST, "No active ANTHROPIC credential configured for this tenant -- PUT /vendor-credentials first"));

        mockMvc.perform(post("/agents/ping-with-tools")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"Hello"}"""))
                .andExpect(status().isBadRequest());
    }
}
