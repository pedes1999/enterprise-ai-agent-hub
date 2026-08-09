package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.AuthResponse;
import com.enterprisehub.dto.LoginRequest;
import com.enterprisehub.dto.RegisterRequest;
import com.enterprisehub.gateway.entity.AppUser;
import com.enterprisehub.gateway.entity.Tenant;
import com.enterprisehub.gateway.repository.AppUserRepository;
import com.enterprisehub.gateway.repository.TenantRepository;
import com.enterprisehub.gateway.security.JwtService;
import com.enterprisehub.gateway.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private TenantRepository tenantRepository;
    private AppUserRepository appUserRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        authService = new AuthService(tenantRepository, appUserRepository, passwordEncoder, jwtService);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private Tenant tenantWithId() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Acme Corp");
        tenant.setSlug("acme");
        return tenant;
    }

    private AppUser userWithId(UUID tenantId, String hash) {
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenantId);
        user.setEmail("admin@acme.com");
        user.setPasswordHash(hash);
        user.setRole("ADMIN");
        return user;
    }

    // ---------- register ----------

    @Test
    void register_happyPath_createsTenantAndAdminUser_andIssuesToken() {
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", "password123");

        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());
        Tenant savedTenant = tenantWithId();
        when(tenantRepository.save(any(Tenant.class))).thenReturn(savedTenant);
        when(appUserRepository.findByTenantIdAndEmail(savedTenant.getId(), "admin@acme.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        AppUser savedUser = userWithId(savedTenant.getId(), "hashed");
        when(appUserRepository.save(any(AppUser.class))).thenReturn(savedUser);
        when(jwtService.issueToken(savedUser.getId().toString(), savedTenant.getId().toString(), "ADMIN"))
                .thenReturn("jwt-token");
        when(jwtService.expirationSeconds()).thenReturn(3600L);

        AuthResponse response = authService.register(request);

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.tenantId()).isEqualTo(savedTenant.getId().toString());
        assertThat(response.tenantSlug()).isEqualTo("acme");
        assertThat(response.role()).isEqualTo("ADMIN");

        ArgumentCaptorHelper.assertUserSavedWithRole(appUserRepository, "ADMIN");
        assertThat(TenantContext.get()).isNull(); // cleared after the call
    }

    @Test
    void register_duplicateTenantSlug_throwsConflict_andNeverTouchesUsers() {
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", "password123");
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenantWithId()));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(appUserRepository);
    }

    @Test
    void register_duplicateEmailWithinTenant_throwsConflict() {
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", "password123");
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());
        Tenant savedTenant = tenantWithId();
        when(tenantRepository.save(any(Tenant.class))).thenReturn(savedTenant);
        when(appUserRepository.findByTenantIdAndEmail(savedTenant.getId(), "admin@acme.com"))
                .thenReturn(Optional.of(userWithId(savedTenant.getId(), "hashed")));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(appUserRepository, never()).save(any());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void register_tenantSaveRace_dataIntegrityViolation_mapsToConflict() {
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", "password123");
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(Tenant.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void register_userSaveRace_dataIntegrityViolation_mapsToConflict() {
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", "password123");
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());
        Tenant savedTenant = tenantWithId();
        when(tenantRepository.save(any(Tenant.class))).thenReturn(savedTenant);
        when(appUserRepository.findByTenantIdAndEmail(savedTenant.getId(), "admin@acme.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(appUserRepository.save(any(AppUser.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void register_blankTenantName_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest(" ", "acme", "admin@acme.com", "password123");
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_blankSlug_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "", "admin@acme.com", "password123");
        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(AuthException.class);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_blankEmail_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "acme", "", "password123");
        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(AuthException.class);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_shortPassword_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "acme", "admin@acme.com", "short");
        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(AuthException.class);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_nullPassword_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "acme", "admin@acme.com", null);
        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(AuthException.class);
        verifyNoInteractions(tenantRepository);
    }

    // ---------- login ----------

    @Test
    void login_happyPath_returnsToken() {
        LoginRequest request = new LoginRequest("acme", "admin@acme.com", "password123");
        Tenant tenant = tenantWithId();
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        AppUser user = userWithId(tenant.getId(), "hashed");
        when(appUserRepository.findByTenantIdAndEmail(tenant.getId(), "admin@acme.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(jwtService.issueToken(user.getId().toString(), tenant.getId().toString(), "ADMIN")).thenReturn("jwt-token");
        when(jwtService.expirationSeconds()).thenReturn(3600L);

        AuthResponse response = authService.login(request);

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(user.getId().toString());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void login_unknownTenantSlug_throwsUnauthorized_genericMessage() {
        LoginRequest request = new LoginRequest("does-not-exist", "admin@acme.com", "password123");
        when(tenantRepository.findBySlug("does-not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AuthException.class)
                .hasMessage("Invalid credentials")
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(appUserRepository);
    }

    @Test
    void login_unknownEmail_throwsUnauthorized_sameGenericMessage() {
        LoginRequest request = new LoginRequest("acme", "ghost@acme.com", "password123");
        Tenant tenant = tenantWithId();
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(appUserRepository.findByTenantIdAndEmail(tenant.getId(), "ghost@acme.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AuthException.class)
                .hasMessage("Invalid credentials");

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void login_wrongPassword_throwsUnauthorized_sameGenericMessage() {
        LoginRequest request = new LoginRequest("acme", "admin@acme.com", "wrongpassword");
        Tenant tenant = tenantWithId();
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        AppUser user = userWithId(tenant.getId(), "hashed");
        when(appUserRepository.findByTenantIdAndEmail(tenant.getId(), "admin@acme.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AuthException.class)
                .hasMessage("Invalid credentials");

        assertThat(TenantContext.get()).isNull();
        verify(jwtService, never()).issueToken(any(), any(), any());
    }

    @Test
    void login_setsTenantContext_duringUserLookup() {
        LoginRequest request = new LoginRequest("acme", "admin@acme.com", "password123");
        Tenant tenant = tenantWithId();
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(appUserRepository.findByTenantIdAndEmail(eq(tenant.getId()), eq("admin@acme.com")))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.get()).isEqualTo(tenant.getId().toString());
                    return Optional.of(userWithId(tenant.getId(), "hashed"));
                });
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(jwtService.expirationSeconds()).thenReturn(3600L);

        authService.login(request);
    }

    /** Small helper to keep the happy-path test's assertions readable. */
    private static final class ArgumentCaptorHelper {
        static void assertUserSavedWithRole(AppUserRepository repo, String expectedRole) {
            verify(repo).save(argThat(user -> expectedRole.equals(user.getRole())));
        }
    }
}
