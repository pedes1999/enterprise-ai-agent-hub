package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.CreateUserRequest;
import com.enterprisehub.dto.UserSummary;
import com.enterprisehub.gateway.entity.AppUser;
import com.enterprisehub.gateway.mail.MailService;
import com.enterprisehub.gateway.repository.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
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
    private final MailService mailService;

    public UserService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder, MailService mailService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
    }

    /**
     * No password comes from the caller (see CreateUserRequest's javadoc) --
     * a temporary one is generated here and emailed, never returned in this
     * method's result or any HTTP response, same "never show a secret back,
     * not even once" discipline as VendorCredentialSummary/ToolCredentialSummary.
     *
     * The email send happens BEFORE the user row is persisted, deliberately:
     * if it fails, account creation aborts entirely rather than leaving a
     * row in the DB with a password nobody -- not the admin, not the new
     * user -- has any way to learn. The reverse ordering (persist then
     * email) would fail worse: a real, usable account existing with a
     * password lost forever the moment email delivery fails.
     */
    public UserSummary create(UUID tenantId, CreateUserRequest request) {
        if (isBlank(request.email()) || isBlank(request.name())) {
            throw new UserManagementException(HttpStatus.BAD_REQUEST, "email and name are required");
        }
        Role role = Role.parse(request.role())
                .orElseThrow(() -> new UserManagementException(HttpStatus.BAD_REQUEST,
                        "role must be one of ADMIN, DEVELOPER, READONLY"));

        if (appUserRepository.findByTenantIdAndEmail(tenantId, request.email()).isPresent()) {
            throw new UserManagementException(HttpStatus.CONFLICT, "Email already registered for this tenant");
        }

        String temporaryPassword = TempPasswordGenerator.generate();
        try {
            mailService.sendTemporaryPassword(request.email(), request.name(), temporaryPassword);
        } catch (MailException e) {
            throw new UserManagementException(HttpStatus.BAD_GATEWAY,
                    "Could not email the temporary password -- account was not created: " + e.getMessage());
        }

        AppUser user = new AppUser();
        user.setTenantId(tenantId);
        user.setEmail(request.email());
        user.setName(request.name());
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
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
        return new UserSummary(user.getId().toString(), user.getEmail(), user.getName(), user.getRole(), user.getCreatedAt());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
