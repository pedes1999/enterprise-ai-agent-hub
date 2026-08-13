package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.AuthResponse;
import com.enterprisehub.gateway.error.GlobalExceptionHandler;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(boolean mustChangePassword) {
        PlatformPrincipal principal = new PlatformPrincipal(userId.toString(), tenantId.toString(), "DEVELOPER", mustChangePassword);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void register_success_returns201WithToken() throws Exception {
        when(authService.register(any())).thenReturn(
                new AuthResponse("jwt-token", 3600, "tenant-1", "acme", "user-1", "admin@acme.com", "ADMIN", false));

        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"tenantName":"Acme","tenantSlug":"acme","email":"admin@acme.com","password":"p@ssword123"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void register_duplicateSlug_returns409WithMessage() throws Exception {
        when(authService.register(any())).thenThrow(new AuthException(HttpStatus.CONFLICT, "Tenant slug already taken"));

        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"tenantName":"Acme","tenantSlug":"acme","email":"admin@acme.com","password":"p@ssword123"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Tenant slug already taken"));
    }

    @Test
    void register_weakPassword_returns400() throws Exception {
        when(authService.register(any())).thenThrow(new AuthException(HttpStatus.BAD_REQUEST, PasswordPolicy.REQUIREMENTS_MESSAGE));

        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"tenantName":"Acme","tenantSlug":"acme","email":"admin@acme.com","password":"allletters"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PasswordPolicy.REQUIREMENTS_MESSAGE));
    }

    @Test
    void login_success_returns200WithToken() throws Exception {
        when(authService.login(any())).thenReturn(
                new AuthResponse("jwt-token", 3600, "tenant-1", "acme", "user-1", "admin@acme.com", "ADMIN", false));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantSlug":"acme","email":"admin@acme.com","password":"password123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"));
    }

    @Test
    void login_invitedUserWithTempPassword_flagsMustChangePassword() throws Exception {
        when(authService.login(any())).thenReturn(
                new AuthResponse("jwt-token", 3600, "tenant-1", "acme", "user-1", "dev@acme.com", "DEVELOPER", true));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantSlug":"acme","email":"dev@acme.com","password":"Tmp9$xyz"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void login_invalidCredentials_returns401WithGenericMessage() throws Exception {
        when(authService.login(any())).thenThrow(new AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantSlug":"acme","email":"admin@acme.com","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void changePassword_success_returnsFreshTokenWithFlagCleared() throws Exception {
        authenticateAs(true);
        when(authService.changePassword(eq(tenantId), eq(userId), any())).thenReturn(
                new AuthResponse("new-jwt-token", 3600, tenantId.toString(), "acme", userId.toString(), "dev@acme.com", "DEVELOPER", false));

        mockMvc.perform(post("/auth/change-password")
                        .contentType("application/json")
                        .content("""
                                {"currentPassword":"Tmp9$xyz","newPassword":"N3w!password"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("new-jwt-token"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void changePassword_noAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/auth/change-password")
                        .contentType("application/json")
                        .content("""
                                {"currentPassword":"Tmp9$xyz","newPassword":"N3w!password"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_wrongCurrentPassword_returns400() throws Exception {
        authenticateAs(true);
        when(authService.changePassword(eq(tenantId), eq(userId), any()))
                .thenThrow(new AuthException(HttpStatus.BAD_REQUEST, "Current password is incorrect"));

        mockMvc.perform(post("/auth/change-password")
                        .contentType("application/json")
                        .content("""
                                {"currentPassword":"wrong","newPassword":"N3w!password"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));
    }

    @Test
    void changePassword_weakNewPassword_returns400() throws Exception {
        authenticateAs(true);
        when(authService.changePassword(eq(tenantId), eq(userId), any()))
                .thenThrow(new AuthException(HttpStatus.BAD_REQUEST, PasswordPolicy.REQUIREMENTS_MESSAGE));

        mockMvc.perform(post("/auth/change-password")
                        .contentType("application/json")
                        .content("""
                                {"currentPassword":"Tmp9$xyz","newPassword":"allletters"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PasswordPolicy.REQUIREMENTS_MESSAGE));
    }
}
