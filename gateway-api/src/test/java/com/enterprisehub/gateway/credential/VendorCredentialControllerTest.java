package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CredentialTestResult;
import com.enterprisehub.dto.TeamVendorCredentialSummary;
import com.enterprisehub.dto.VendorCredentialSummary;
import com.enterprisehub.gateway.error.GlobalExceptionHandler;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
    private VendorModelCatalogService vendorModelCatalogService;
    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vendorCredentialService = mock(VendorCredentialService.class);
        vendorCredentialTestService = mock(VendorCredentialTestService.class);
        vendorModelCatalogService = mock(VendorModelCatalogService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new VendorCredentialController(vendorCredentialService, vendorCredentialTestService, vendorModelCatalogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        authenticateAs("ADMIN");
    }

    /** DEVELOPER-role tests re-authenticate via this -- see put/list/delete/test/models being open to DEVELOPER now, unlike the old ADMIN-only gate. */
    private void authenticateAs(String role) {
        PlatformPrincipal principal = new PlatformPrincipal(userId.toString(), tenantId.toString(), role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void put_returns200_withNoTokenField() throws Exception {
        when(vendorCredentialService.put(eq(tenantId), eq(userId), any())).thenReturn(
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
    void put_asDeveloper_alsoAllowed() throws Exception {
        // Was ADMIN-only before -- the whole point of this change is that a
        // DEVELOPER can bring their own key now.
        authenticateAs("DEVELOPER");
        when(vendorCredentialService.put(eq(tenantId), eq(userId), any())).thenReturn(
                new VendorCredentialSummary(UUID.randomUUID().toString(), "ANTHROPIC", true, Instant.now(), Instant.now(), null, null));

        mockMvc.perform(put("/vendor-credentials")
                        .contentType("application/json")
                        .content("""
                                {"provider":"anthropic","token":"sk-ant-secret"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void put_invalidProvider_returns400() throws Exception {
        when(vendorCredentialService.put(eq(tenantId), eq(userId), any()))
                .thenThrow(new VendorCredentialException(HttpStatus.BAD_REQUEST, "provider must be one of ANTHROPIC, OPENAI, GEMINI"));

        mockMvc.perform(put("/vendor-credentials")
                        .contentType("application/json")
                        .content("""
                                {"provider":"COHERE","token":"secret"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsCallersOwnSummaries() throws Exception {
        when(vendorCredentialService.list(tenantId, userId)).thenReturn(List.of(
                new VendorCredentialSummary(UUID.randomUUID().toString(), "OPENAI", true, Instant.now(), Instant.now(), null, null)));

        mockMvc.perform(get("/vendor-credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("OPENAI"));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/vendor-credentials/GEMINI"))
                .andExpect(status().isNoContent());

        verify(vendorCredentialService).delete(tenantId, userId, "GEMINI");
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        doThrow(new VendorCredentialException(HttpStatus.NOT_FOUND, "No credential stored for provider GEMINI"))
                .when(vendorCredentialService).delete(tenantId, userId, "GEMINI");

        mockMvc.perform(delete("/vendor-credentials/GEMINI"))
                .andExpect(status().isNotFound());
    }

    @Test
    void test_validCredential_returnsValidTrue() throws Exception {
        when(vendorCredentialTestService.test(tenantId, userId, "ANTHROPIC"))
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
        when(vendorCredentialTestService.test(tenantId, userId, "ANTHROPIC"))
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
        when(vendorCredentialTestService.test(tenantId, userId, "ANTHROPIC"))
                .thenThrow(new VendorCredentialException(HttpStatus.NOT_FOUND, "No active credential stored for provider ANTHROPIC"));

        mockMvc.perform(post("/vendor-credentials/test")
                        .contentType("application/json")
                        .content("""
                                {"provider":"ANTHROPIC"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void listModels_delegatesToService_returnsOptions() throws Exception {
        when(vendorModelCatalogService.list(tenantId, userId, "ANTHROPIC"))
                .thenReturn(List.of(new com.enterprisehub.dto.ModelOption("claude-opus-4-1-20250805", "Claude Opus 4.1")));

        mockMvc.perform(get("/vendor-credentials/ANTHROPIC/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("claude-opus-4-1-20250805"))
                .andExpect(jsonPath("$[0].label").value("Claude Opus 4.1"));
    }

    @Test
    void listModels_noActiveCredential_returns404() throws Exception {
        when(vendorModelCatalogService.list(tenantId, userId, "LOCAL"))
                .thenThrow(new VendorCredentialException(HttpStatus.NOT_FOUND, "No active credential stored for provider LOCAL"));

        mockMvc.perform(get("/vendor-credentials/LOCAL/models"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listTeam_admin_returnsCrossUserSummaries() throws Exception {
        when(vendorCredentialService.listForTeam(tenantId)).thenReturn(List.of(
                new TeamVendorCredentialSummary(userId.toString(), "me@acme.com", "ANTHROPIC", true, null, null)));

        mockMvc.perform(get("/vendor-credentials/team"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userEmail").value("me@acme.com"));
    }

    // Role enforcement (@PreAuthorize) isn't active under standaloneSetup --
    // covered instead by RolesAndCredentialsIntegrationTest's full Spring
    // Security context.

    @Test
    void deactivateTeamCredential_admin_returns204() throws Exception {
        UUID targetUserId = UUID.randomUUID();

        mockMvc.perform(post("/vendor-credentials/team/" + targetUserId + "/ANTHROPIC/deactivate"))
                .andExpect(status().isNoContent());

        verify(vendorCredentialService).deactivateForUser(tenantId, targetUserId, "ANTHROPIC");
    }
}
