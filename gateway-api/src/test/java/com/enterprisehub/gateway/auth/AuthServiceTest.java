package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.AuthResponse;
import com.enterprisehub.dto.ChangePasswordRequest;
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

    private static final String STRONG_PASSWORD = "p@ssword123";

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
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", STRONG_PASSWORD);

        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());
        Tenant savedTenant = tenantWithId();
        when(tenantRepository.save(any(Tenant.class))).thenReturn(savedTenant);
        when(appUserRepository.findByTenantIdAndEmail(savedTenant.getId(), "admin@acme.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(STRONG_PASSWORD)).thenReturn("hashed");
        AppUser savedUser = userWithId(savedTenant.getId(), "hashed");
        when(appUserRepository.save(any(AppUser.class))).thenReturn(savedUser);
        when(jwtService.issueToken(savedUser.getId().toString(), savedTenant.getId().toString(), "ADMIN", false))
                .thenReturn("jwt-token");
        when(jwtService.expirationSeconds()).thenReturn(3600L);

        AuthResponse response = authService.register(request);

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.tenantId()).isEqualTo(savedTenant.getId().toString());
        assertThat(response.tenantSlug()).isEqualTo("acme");
        assertThat(response.role()).isEqualTo("ADMIN");
        // Self-chosen up front -- a self-registered admin is never forced
        // through the change-password flow, unlike an admin-invited user.
        assertThat(response.mustChangePassword()).isFalse();

        ArgumentCaptorHelper.assertUserSavedWithRole(appUserRepository, "ADMIN");
        assertThat(TenantContext.get()).isNull(); // cleared after the call
    }

    @Test
    void register_duplicateTenantSlug_throwsConflict_andNeverTouchesUsers() {
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", STRONG_PASSWORD);
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenantWithId()));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(appUserRepository);
    }

    @Test
    void register_duplicateEmailWithinTenant_throwsConflict() {
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", STRONG_PASSWORD);
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
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", STRONG_PASSWORD);
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(Tenant.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void register_userSaveRace_dataIntegrityViolation_mapsToConflict() {
        RegisterRequest request = new RegisterRequest("Acme Corp", "acme", "admin@acme.com", STRONG_PASSWORD);
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
        RegisterRequest request = new RegisterRequest(" ", "acme", "admin@acme.com", STRONG_PASSWORD);
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_blankSlug_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "", "admin@acme.com", STRONG_PASSWORD);
        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(AuthException.class);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_blankEmail_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "acme", "", STRONG_PASSWORD);
        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(AuthException.class);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_shortPassword_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "acme", "admin@acme.com", "sh0rt!");
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .hasMessage(PasswordPolicy.REQUIREMENTS_MESSAGE);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_nullPassword_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "acme", "admin@acme.com", null);
        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(AuthException.class);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_passwordWithNoDigit_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "acme", "admin@acme.com", "p@ssword!!");
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .hasMessage(PasswordPolicy.REQUIREMENTS_MESSAGE);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void register_passwordWithNoSpecialCharacter_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest("Acme", "acme", "admin@acme.com", "password123");
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthException.class)
                .hasMessage(PasswordPolicy.REQUIREMENTS_MESSAGE);
        verifyNoInteractions(tenantRepository);
    }

    // ---------- login ----------

    @Test
    void login_happyPath_returnsToken() {
        LoginRequest request = new LoginRequest("acme", "admin@acme.com", STRONG_PASSWORD);
        Tenant tenant = tenantWithId();
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        AppUser user = userWithId(tenant.getId(), "hashed");
        when(appUserRepository.findByTenantIdAndEmail(tenant.getId(), "admin@acme.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(STRONG_PASSWORD, "hashed")).thenReturn(true);
        when(jwtService.issueToken(user.getId().toString(), tenant.getId().toString(), "ADMIN", false)).thenReturn("jwt-token");
        when(jwtService.expirationSeconds()).thenReturn(3600L);

        AuthResponse response = authService.login(request);

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(user.getId().toString());
        assertThat(response.mustChangePassword()).isFalse();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void login_userStillOnTemporaryPassword_responseFlagsMustChangePassword() {
        LoginRequest request = new LoginRequest("acme", "admin@acme.com", "Tmp9$xyz");
        Tenant tenant = tenantWithId();
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        AppUser user = userWithId(tenant.getId(), "hashed");
        user.setMustChangePassword(true);
        when(appUserRepository.findByTenantIdAndEmail(tenant.getId(), "admin@acme.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Tmp9$xyz", "hashed")).thenReturn(true);
        when(jwtService.issueToken(user.getId().toString(), tenant.getId().toString(), "ADMIN", true)).thenReturn("jwt-token");
        when(jwtService.expirationSeconds()).thenReturn(3600L);

        AuthResponse response = authService.login(request);

        assertThat(response.mustChangePassword()).isTrue();
    }

    @Test
    void login_unknownTenantSlug_throwsUnauthorized_genericMessage() {
        LoginRequest request = new LoginRequest("does-not-exist", "admin@acme.com", STRONG_PASSWORD);
        when(tenantRepository.findBySlug("does-not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AuthException.class)
                .hasMessage("Invalid credentials")
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(appUserRepository);
    }

    @Test
    void login_unknownEmail_throwsUnauthorized_sameGenericMessage() {
        LoginRequest request = new LoginRequest("acme", "ghost@acme.com", STRONG_PASSWORD);
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
        verify(jwtService, never()).issueToken(any(), any(), any(), anyBoolean());
    }

    @Test
    void login_setsTenantContext_duringUserLookup() {
        LoginRequest request = new LoginRequest("acme", "admin@acme.com", STRONG_PASSWORD);
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

    // ---------- changePassword ----------

    @Test
    void changePassword_happyPath_updatesHash_clearsFlag_reissuesToken() {
        Tenant tenant = tenantWithId();
        AppUser user = userWithId(tenant.getId(), "old-hash");
        user.setMustChangePassword(true);
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(passwordEncoder.matches("Tmp9$xyz", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches(STRONG_PASSWORD, "old-hash")).thenReturn(false);
        when(passwordEncoder.encode(STRONG_PASSWORD)).thenReturn("new-hash");
        when(jwtService.issueToken(user.getId().toString(), tenant.getId().toString(), "ADMIN", false)).thenReturn("new-jwt");
        when(jwtService.expirationSeconds()).thenReturn(3600L);

        AuthResponse response = authService.changePassword(tenant.getId(), user.getId(),
                new ChangePasswordRequest("Tmp9$xyz", STRONG_PASSWORD));

        assertThat(response.token()).isEqualTo("new-jwt");
        assertThat(response.mustChangePassword()).isFalse();
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.isMustChangePassword()).isFalse();
        verify(appUserRepository).save(user);
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsBadRequest_neverSaves() {
        Tenant tenant = tenantWithId();
        AppUser user = userWithId(tenant.getId(), "old-hash");
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(tenant.getId(), user.getId(),
                new ChangePasswordRequest("wrong", STRONG_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .hasMessage("Current password is incorrect")
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(appUserRepository, never()).save(any());
    }

    @Test
    void changePassword_weakNewPassword_throwsBadRequest() {
        Tenant tenant = tenantWithId();
        AppUser user = userWithId(tenant.getId(), "old-hash");
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Tmp9$xyz", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword(tenant.getId(), user.getId(),
                new ChangePasswordRequest("Tmp9$xyz", "allletters")))
                .isInstanceOf(AuthException.class)
                .hasMessage(PasswordPolicy.REQUIREMENTS_MESSAGE);

        verify(appUserRepository, never()).save(any());
    }

    @Test
    void changePassword_newPasswordSameAsCurrent_throwsBadRequest() {
        Tenant tenant = tenantWithId();
        AppUser user = userWithId(tenant.getId(), "old-hash");
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(STRONG_PASSWORD, "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword(tenant.getId(), user.getId(),
                new ChangePasswordRequest(STRONG_PASSWORD, STRONG_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .hasMessage("New password must be different from your current password");

        verify(appUserRepository, never()).save(any());
    }

    @Test
    void changePassword_unknownUser_throwsUnauthorized() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(appUserRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.changePassword(tenantId, userId,
                new ChangePasswordRequest("Tmp9$xyz", STRONG_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    /** Small helper to keep the happy-path test's assertions readable. */
    private static final class ArgumentCaptorHelper {
        static void assertUserSavedWithRole(AppUserRepository repo, String expectedRole) {
            verify(repo).save(argThat(user -> expectedRole.equals(user.getRole())));
        }
    }
}
