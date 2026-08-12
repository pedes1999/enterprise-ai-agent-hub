package com.enterprisehub.gateway.tenant;

import com.enterprisehub.dto.LlmProviderAvailability;
import com.enterprisehub.dto.TenantSettingsResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantSettingsControllerTest {

    private TenantSettingsService tenantSettingsService;
    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantSettingsService = mock(TenantSettingsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TenantSettingsController(tenantSettingsService))
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
    void get_returnsPreferenceAndAvailability() throws Exception {
        when(tenantSettingsService.get(tenantId)).thenReturn(new TenantSettingsResponse(
                "LOCAL", List.of(new LlmProviderAvailability("ANTHROPIC", true), new LlmProviderAvailability("LOCAL", true))));

        mockMvc.perform(get("/tenant-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLlmProvider").value("LOCAL"))
                .andExpect(jsonPath("$.availableProviders[1].provider").value("LOCAL"));
    }

    @Test
    void put_delegatesToService_returnsUpdatedSettings() throws Exception {
        when(tenantSettingsService.update(eq(tenantId), any())).thenReturn(new TenantSettingsResponse(
                "LOCAL", List.of(new LlmProviderAvailability("LOCAL", true))));

        mockMvc.perform(put("/tenant-settings")
                        .contentType("application/json")
                        .content("""
                                {"preferredLlmProvider":"local"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLlmProvider").value("LOCAL"));
    }

    @Test
    void put_serviceRejectsUnknownProvider_returns400() throws Exception {
        when(tenantSettingsService.update(eq(tenantId), any()))
                .thenThrow(new TenantSettingsException(HttpStatus.BAD_REQUEST, "preferredLlmProvider must be one of ANTHROPIC, OPENAI, GEMINI, LOCAL"));

        mockMvc.perform(put("/tenant-settings")
                        .contentType("application/json")
                        .content("""
                                {"preferredLlmProvider":"COHERE"}"""))
                .andExpect(status().isBadRequest());
    }
}
