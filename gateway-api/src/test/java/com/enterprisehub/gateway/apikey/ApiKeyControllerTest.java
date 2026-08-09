package com.enterprisehub.gateway.apikey;

import com.enterprisehub.dto.ApiKeyCreatedResponse;
import com.enterprisehub.dto.ApiKeySummary;
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

class ApiKeyControllerTest {

    private ApiKeyService apiKeyService;
    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ApiKeyController(apiKeyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        PlatformPrincipal principal = new PlatformPrincipal("user-1", tenantId.toString(), "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_returns201WithRawKey() throws Exception {
        when(apiKeyService.create(eq(tenantId), eq("ci-key")))
                .thenReturn(new ApiKeyCreatedResponse(UUID.randomUUID().toString(), "ci-key", "ahk_raw"));

        mockMvc.perform(post("/api-keys")
                        .contentType("application/json")
                        .content("""
                                {"label":"ci-key"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rawKey").value("ahk_raw"))
                .andExpect(jsonPath("$.label").value("ci-key"));
    }

    @Test
    void list_returnsSummariesForCallersTenant_neverRawKey() throws Exception {
        when(apiKeyService.list(tenantId)).thenReturn(List.of(
                new ApiKeySummary(UUID.randomUUID().toString(), "ci-key", null, null, Instant.now())));

        mockMvc.perform(get("/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("ci-key"))
                .andExpect(jsonPath("$[0].rawKey").doesNotExist());
    }

    @Test
    void revoke_ownedKey_returns204() throws Exception {
        UUID keyId = UUID.randomUUID();

        mockMvc.perform(delete("/api-keys/" + keyId))
                .andExpect(status().isNoContent());

        verify(apiKeyService).revoke(tenantId, keyId);
    }

    @Test
    void revoke_unknownKey_returns404() throws Exception {
        UUID keyId = UUID.randomUUID();
        doThrow(new ApiKeyException(HttpStatus.NOT_FOUND, "API key not found"))
                .when(apiKeyService).revoke(tenantId, keyId);

        mockMvc.perform(delete("/api-keys/" + keyId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("API key not found"));
    }
}
