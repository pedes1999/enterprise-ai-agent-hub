package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CredentialTestResult;
import com.enterprisehub.dto.VendorCredentialSummary;
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

class VendorCredentialControllerTest {

    private VendorCredentialService vendorCredentialService;
    private VendorCredentialTestService vendorCredentialTestService;
    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialService = mock(VendorCredentialService.class);
        vendorCredentialTestService = mock(VendorCredentialTestService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new VendorCredentialController(vendorCredentialService, vendorCredentialTestService))
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
    void put_returns200_withNoTokenField() throws Exception {
        when(vendorCredentialService.put(eq(tenantId), any())).thenReturn(
                new VendorCredentialSummary(UUID.randomUUID().toString(), "ANTHROPIC", true, Instant.now(), Instant.now(), null, null));

        mockMvc.perform(put("/vendor-credentials")
                        .contentType("application/json")
                        .content("""
                                {"provider":"anthropic","token":"sk-ant-secret"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void put_invalidProvider_returns400() throws Exception {
        when(vendorCredentialService.put(eq(tenantId), any()))
                .thenThrow(new VendorCredentialException(HttpStatus.BAD_REQUEST, "provider must be one of ANTHROPIC, OPENAI, GEMINI"));

        mockMvc.perform(put("/vendor-credentials")
                        .contentType("application/json")
                        .content("""
                                {"provider":"COHERE","token":"secret"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsSummaries() throws Exception {
        when(vendorCredentialService.list(tenantId)).thenReturn(List.of(
                new VendorCredentialSummary(UUID.randomUUID().toString(), "OPENAI", true, Instant.now(), Instant.now(), null, null)));

        mockMvc.perform(get("/vendor-credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("OPENAI"));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/vendor-credentials/GEMINI"))
                .andExpect(status().isNoContent());

        verify(vendorCredentialService).delete(tenantId, "GEMINI");
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        doThrow(new VendorCredentialException(HttpStatus.NOT_FOUND, "No credential stored for provider GEMINI"))
                .when(vendorCredentialService).delete(tenantId, "GEMINI");

        mockMvc.perform(delete("/vendor-credentials/GEMINI"))
                .andExpect(status().isNotFound());
    }

    @Test
    void test_validCredential_returnsValidTrue() throws Exception {
        when(vendorCredentialTestService.test(tenantId, "ANTHROPIC"))
                .thenReturn(new CredentialTestResult(true, "Anthropic credential is valid."));

        mockMvc.perform(post("/vendor-credentials/test")
                        .contentType("application/json")
                        .content("""
                                {"provider":"ANTHROPIC"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void test_rejectedCredential_returnsValidFalse_stillHttp200() throws Exception {
        when(vendorCredentialTestService.test(tenantId, "ANTHROPIC"))
                .thenReturn(new CredentialTestResult(false, "Anthropic rejected this credential: 401 Unauthorized"));

        mockMvc.perform(post("/vendor-credentials/test")
                        .contentType("application/json")
                        .content("""
                                {"provider":"ANTHROPIC"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void test_noCredentialStored_returns404() throws Exception {
        when(vendorCredentialTestService.test(tenantId, "ANTHROPIC"))
                .thenThrow(new VendorCredentialException(HttpStatus.NOT_FOUND, "No active credential stored for provider ANTHROPIC"));

        mockMvc.perform(post("/vendor-credentials/test")
                        .contentType("application/json")
                        .content("""
                                {"provider":"ANTHROPIC"}"""))
                .andExpect(status().isNotFound());
    }
}
