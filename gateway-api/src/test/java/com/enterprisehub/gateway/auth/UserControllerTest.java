package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.UserSummary;
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

class UserControllerTest {

    private UserService userService;
    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
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
    void create_returns201() throws Exception {
        when(userService.create(eq(tenantId), any())).thenReturn(
                new UserSummary(UUID.randomUUID().toString(), "dev@acme.com", "DEVELOPER", Instant.now()));

        mockMvc.perform(post("/users")
                        .contentType("application/json")
                        .content("""
                                {"email":"dev@acme.com","password":"password123","role":"DEVELOPER"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("DEVELOPER"));
    }

    @Test
    void create_invalidRole_returns400() throws Exception {
        when(userService.create(eq(tenantId), any()))
                .thenThrow(new UserManagementException(HttpStatus.BAD_REQUEST, "role must be one of ADMIN, DEVELOPER, READONLY"));

        mockMvc.perform(post("/users")
                        .contentType("application/json")
                        .content("""
                                {"email":"dev@acme.com","password":"password123","role":"SUPERUSER"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsUsers() throws Exception {
        when(userService.list(tenantId)).thenReturn(List.of(
                new UserSummary(UUID.randomUUID().toString(), "admin@acme.com", "ADMIN", Instant.now())));

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("admin@acme.com"));
    }

    @Test
    void updateRole_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userService.updateRole(eq(tenantId), eq(userId), eq("READONLY"))).thenReturn(
                new UserSummary(userId.toString(), "dev@acme.com", "READONLY", Instant.now()));

        mockMvc.perform(patch("/users/" + userId + "/role")
                        .contentType("application/json")
                        .content("""
                                {"role":"READONLY"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("READONLY"));
    }

    @Test
    void updateRole_lastAdmin_returns409() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userService.updateRole(eq(tenantId), eq(userId), any()))
                .thenThrow(new UserManagementException(HttpStatus.CONFLICT, "Cannot remove the tenant's last remaining admin"));

        mockMvc.perform(patch("/users/" + userId + "/role")
                        .contentType("application/json")
                        .content("""
                                {"role":"DEVELOPER"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    void delete_returns204() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/users/" + userId))
                .andExpect(status().isNoContent());

        verify(userService).delete(tenantId, userId);
    }
}
