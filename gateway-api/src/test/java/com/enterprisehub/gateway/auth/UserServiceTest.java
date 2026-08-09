package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.CreateUserRequest;
import com.enterprisehub.dto.UserSummary;
import com.enterprisehub.gateway.entity.AppUser;
import com.enterprisehub.gateway.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private AppUserRepository repository;
    private PasswordEncoder passwordEncoder;
    private UserService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new UserService(repository, passwordEncoder);
    }

    private AppUser user(UUID id, UUID tenantId, String email, String role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setRole(role);
        user.setPasswordHash("hashed");
        return user;
    }

    // ---------- create ----------

    @Test
    void create_happyPath_savesWithHashedPasswordAndValidatedRole() {
        when(repository.findByTenantIdAndEmail(tenantId, "dev@acme.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        UUID newId = UUID.randomUUID();
        when(repository.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            u.setId(newId);
            return u;
        });

        UserSummary summary = service.create(tenantId, new CreateUserRequest("dev@acme.com", "password123", "DEVELOPER"));

        assertThat(summary.email()).isEqualTo("dev@acme.com");
        assertThat(summary.role()).isEqualTo("DEVELOPER");
        verify(repository).save(argThat(u -> "hashed".equals(u.getPasswordHash())));
    }

    @Test
    void create_invalidRole_throwsBadRequest() {
        when(repository.findByTenantIdAndEmail(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(tenantId, new CreateUserRequest("x@acme.com", "password123", "SUPERUSER")))
                .isInstanceOf(UserManagementException.class)
                .satisfies(e -> assertThat(((UserManagementException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void create_shortPassword_throwsBadRequest() {
        assertThatThrownBy(() -> service.create(tenantId, new CreateUserRequest("x@acme.com", "short", "DEVELOPER")))
                .isInstanceOf(UserManagementException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void create_duplicateEmail_throwsConflict() {
        when(repository.findByTenantIdAndEmail(tenantId, "dev@acme.com"))
                .thenReturn(Optional.of(user(UUID.randomUUID(), tenantId, "dev@acme.com", "DEVELOPER")));

        assertThatThrownBy(() -> service.create(tenantId, new CreateUserRequest("dev@acme.com", "password123", "DEVELOPER")))
                .isInstanceOf(UserManagementException.class)
                .satisfies(e -> assertThat(((UserManagementException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void create_raceOnSave_mapsDataIntegrityViolationToConflict() {
        when(repository.findByTenantIdAndEmail(any(), any())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.create(tenantId, new CreateUserRequest("x@acme.com", "password123", "DEVELOPER")))
                .isInstanceOf(UserManagementException.class)
                .satisfies(e -> assertThat(((UserManagementException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ---------- list ----------

    @Test
    void list_returnsAllUsersForTenant() {
        when(repository.findByTenantId(tenantId)).thenReturn(List.of(
                user(UUID.randomUUID(), tenantId, "a@acme.com", "ADMIN"),
                user(UUID.randomUUID(), tenantId, "b@acme.com", "DEVELOPER")));

        assertThat(service.list(tenantId)).hasSize(2);
    }

    // ---------- updateRole ----------

    @Test
    void updateRole_developerToReadonly_succeeds() {
        UUID userId = UUID.randomUUID();
        AppUser existing = user(userId, tenantId, "dev@acme.com", "DEVELOPER");
        when(repository.findById(userId)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserSummary updated = service.updateRole(tenantId, userId, "READONLY");

        assertThat(updated.role()).isEqualTo("READONLY");
    }

    @Test
    void updateRole_demotingLastAdmin_throwsConflict() {
        UUID userId = UUID.randomUUID();
        AppUser existing = user(userId, tenantId, "admin@acme.com", "ADMIN");
        when(repository.findById(userId)).thenReturn(Optional.of(existing));
        when(repository.countByTenantIdAndRole(tenantId, "ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> service.updateRole(tenantId, userId, "DEVELOPER"))
                .isInstanceOf(UserManagementException.class)
                .satisfies(e -> assertThat(((UserManagementException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).save(any());
    }

    @Test
    void updateRole_demotingOneOfSeveralAdmins_succeeds() {
        UUID userId = UUID.randomUUID();
        AppUser existing = user(userId, tenantId, "admin2@acme.com", "ADMIN");
        when(repository.findById(userId)).thenReturn(Optional.of(existing));
        when(repository.countByTenantIdAndRole(tenantId, "ADMIN")).thenReturn(2L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserSummary updated = service.updateRole(tenantId, userId, "DEVELOPER");
        assertThat(updated.role()).isEqualTo("DEVELOPER");
    }

    @Test
    void updateRole_userFromAnotherTenant_throwsNotFound() {
        UUID userId = UUID.randomUUID();
        AppUser foreignUser = user(userId, UUID.randomUUID(), "other@globex.com", "DEVELOPER");
        when(repository.findById(userId)).thenReturn(Optional.of(foreignUser));

        assertThatThrownBy(() -> service.updateRole(tenantId, userId, "ADMIN"))
                .isInstanceOf(UserManagementException.class)
                .satisfies(e -> assertThat(((UserManagementException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void updateRole_invalidRole_throwsBadRequest() {
        UUID userId = UUID.randomUUID();
        when(repository.findById(userId)).thenReturn(Optional.of(user(userId, tenantId, "x@acme.com", "DEVELOPER")));

        assertThatThrownBy(() -> service.updateRole(tenantId, userId, "SUPERUSER"))
                .isInstanceOf(UserManagementException.class)
                .satisfies(e -> assertThat(((UserManagementException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateRole_unknownUserId_throwsNotFound() {
        UUID userId = UUID.randomUUID();
        when(repository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRole(tenantId, userId, "ADMIN"))
                .isInstanceOf(UserManagementException.class)
                .satisfies(e -> assertThat(((UserManagementException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------- delete ----------

    @Test
    void delete_nonAdminUser_succeeds() {
        UUID userId = UUID.randomUUID();
        AppUser existing = user(userId, tenantId, "dev@acme.com", "DEVELOPER");
        when(repository.findById(userId)).thenReturn(Optional.of(existing));

        service.delete(tenantId, userId);

        verify(repository).delete(existing);
    }

    @Test
    void delete_lastAdmin_throwsConflict() {
        UUID userId = UUID.randomUUID();
        AppUser existing = user(userId, tenantId, "admin@acme.com", "ADMIN");
        when(repository.findById(userId)).thenReturn(Optional.of(existing));
        when(repository.countByTenantIdAndRole(tenantId, "ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(tenantId, userId))
                .isInstanceOf(UserManagementException.class)
                .satisfies(e -> assertThat(((UserManagementException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).delete(any(AppUser.class));
    }

    @Test
    void delete_oneOfSeveralAdmins_succeeds() {
        UUID userId = UUID.randomUUID();
        AppUser existing = user(userId, tenantId, "admin2@acme.com", "ADMIN");
        when(repository.findById(userId)).thenReturn(Optional.of(existing));
        when(repository.countByTenantIdAndRole(tenantId, "ADMIN")).thenReturn(2L);

        service.delete(tenantId, userId);

        verify(repository).delete(existing);
    }
}
