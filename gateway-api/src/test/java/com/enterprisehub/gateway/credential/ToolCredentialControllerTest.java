package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.ToolCredentialSummary;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ToolCredentialControllerTest {

    private ToolCredentialService toolCredentialService;
    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        toolCredentialService = mock(ToolCredentialService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ToolCredentialController(toolCredentialService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        PlatformPrincipal principal = new PlatformPrincipal("admin-1", tenantId.toString(), "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void put_returns200_withNoValueField() throws Exception {
        when(toolCredentialService.put(eq(tenantId), any())).thenReturn(
                new ToolCredentialSummary(UUID.randomUUID().toString(), "GIT", true, Instant.now(), Instant.now()));

        mockMvc.perform(put("/tool-credentials")
                        .contentType("application/json")
                        .content("""
                                {"credentialKind":"git","value":"ghp_secret"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialKind").value("GIT"))
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    @Test
    void put_invalidKind_returns400() throws Exception {
        when(toolCredentialService.put(eq(tenantId), any()))
                .thenThrow(new ToolCredentialException(HttpStatus.BAD_REQUEST, "credentialKind must be one of GIT"));

        mockMvc.perform(put("/tool-credentials")
                        .contentType("application/json")
                        .content("""
                                {"credentialKind":"SSH","value":"secret"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsSummaries() throws Exception {
        when(toolCredentialService.list(tenantId)).thenReturn(List.of(
                new ToolCredentialSummary(UUID.randomUUID().toString(), "GIT", true, Instant.now(), Instant.now())));

        mockMvc.perform(get("/tool-credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].credentialKind").value("GIT"));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/tool-credentials/GIT"))
                .andExpect(status().isNoContent());

        verify(toolCredentialService).delete(tenantId, "GIT");
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        doThrow(new ToolCredentialException(HttpStatus.NOT_FOUND, "No credential stored for kind GIT"))
                .when(toolCredentialService).delete(tenantId, "GIT");

        mockMvc.perform(delete("/tool-credentials/GIT"))
                .andExpect(status().isNotFound());
    }
}
