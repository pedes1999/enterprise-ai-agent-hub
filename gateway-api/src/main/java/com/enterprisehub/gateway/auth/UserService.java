package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.CreateUserRequest;
import com.enterprisehub.dto.UserSummary;
import com.enterprisehub.gateway.entity.AppUser;
import com.enterprisehub.gateway.repository.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Called only from already-authenticated, ADMIN-only endpoints (see
 * UserController's class-level @PreAuthorize), so unlike AuthService there's
 * no manual TenantContext bootstrapping here -- TenantResolvingFilter has
 * already set it for the request, and app_users' RLS policy (unlike
 * platform_api_keys') is fully closed, so reads are DB-enforced too.
 */
@Service
public class UserService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserSummary create(UUID tenantId, CreateUserRequest request) {
        if (isBlank(request.email()) || request.password() == null || request.password().length() < 8) {
            throw new UserManagementException(HttpStatus.BAD_REQUEST,
                    "email is required and password must be at least 8 characters");
        }
        Role role = Role.parse(request.role())
                .orElseThrow(() -> new UserManagementException(HttpStatus.BAD_REQUEST,
                        "role must be one of ADMIN, DEVELOPER, READONLY"));

        if (appUserRepository.findByTenantIdAndEmail(tenantId, request.email()).isPresent()) {
            throw new UserManagementException(HttpStatus.CONFLICT, "Email already registered for this tenant");
        }

        AppUser user = new AppUser();
        user.setTenantId(tenantId);
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(role.name());

        try {
            user = appUserRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new UserManagementException(HttpStatus.CONFLICT, "Email already registered for this tenant");
        }

        return toSummary(user);
    }

    public List<UserSummary> list(UUID tenantId) {
        return appUserRepository.findByTenantId(tenantId).stream()
                .map(this::toSummary)
                .toList();
    }

    public UserSummary updateRole(UUID tenantId, UUID userId, String requestedRole) {
        AppUser user = findInTenant(tenantId, userId);
        Role newRole = Role.parse(requestedRole)
                .orElseThrow(() -> new UserManagementException(HttpStatus.BAD_REQUEST,
                        "role must be one of ADMIN, DEVELOPER, READONLY"));

        if ("ADMIN".equals(user.getRole()) && newRole != Role.ADMIN) {
            guardAgainstRemovingLastAdmin(tenantId);
        }

        user.setRole(newRole.name());
        return toSummary(appUserRepository.save(user));
    }

    public void delete(UUID tenantId, UUID userId) {
        AppUser user = findInTenant(tenantId, userId);
        if ("ADMIN".equals(user.getRole())) {
            guardAgainstRemovingLastAdmin(tenantId);
        }
        appUserRepository.delete(user);
    }

    private void guardAgainstRemovingLastAdmin(UUID tenantId) {
        if (appUserRepository.countByTenantIdAndRole(tenantId, "ADMIN") <= 1) {
            throw new UserManagementException(HttpStatus.CONFLICT,
                    "Cannot remove the tenant's last remaining admin");
        }
    }

    private AppUser findInTenant(UUID tenantId, UUID userId) {
        // RLS already scopes this to the caller's tenant; findById returning
        // empty covers both "doesn't exist" and "belongs to another tenant"
        // indistinguishably, which is the correct behavior either way.
        return appUserRepository.findById(userId)
                .filter(user -> user.getTenantId().equals(tenantId))
                .orElseThrow(() -> new UserManagementException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private UserSummary toSummary(AppUser user) {
        return new UserSummary(user.getId().toString(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
