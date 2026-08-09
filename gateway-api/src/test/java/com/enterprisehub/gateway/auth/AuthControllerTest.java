package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.AuthResponse;
import com.enterprisehub.gateway.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void register_success_returns201WithToken() throws Exception {
        when(authService.register(any())).thenReturn(
                new AuthResponse("jwt-token", 3600, "tenant-1", "acme", "user-1", "admin@acme.com", "ADMIN"));

        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"tenantName":"Acme","tenantSlug":"acme","email":"admin@acme.com","password":"password123"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void register_duplicateSlug_returns409WithMessage() throws Exception {
        when(authService.register(any())).thenThrow(new AuthException(HttpStatus.CONFLICT, "Tenant slug already taken"));

        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"tenantName":"Acme","tenantSlug":"acme","email":"admin@acme.com","password":"password123"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Tenant slug already taken"));
    }

    @Test
    void login_success_returns200WithToken() throws Exception {
        when(authService.login(any())).thenReturn(
                new AuthResponse("jwt-token", 3600, "tenant-1", "acme", "user-1", "admin@acme.com", "ADMIN"));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantSlug":"acme","email":"admin@acme.com","password":"password123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"));
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
}
