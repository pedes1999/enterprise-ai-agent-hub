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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Both register() and login() run partly BEFORE any tenant is known to the
 * caller -- there's no JWT yet to resolve one from. TenantContext is set
 * manually here once the tenant is identified (by slug, or freshly created)
 * so the RLS-scoped app_users queries below it succeed, then cleared in a
 * finally block. Deliberately NOT @Transactional at the method level: each
 * repository call checks out its own JDBC connection (see
 * TenantAwareDataSource), which reads TenantContext at THAT checkout --
 * wrapping this whole method in one outer @Transactional would check out
 * the connection once at the very start, before the tenant is even
 * resolved (register() doesn't know the new tenant's id yet at that point).
 */
@Service
public class AuthService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(TenantRepository tenantRepository,
                        AppUserRepository appUserRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService) {
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        validateRegisterRequest(request);

        if (tenantRepository.findBySlug(request.tenantSlug()).isPresent()) {
            throw new AuthException(HttpStatus.CONFLICT, "Tenant slug already taken");
        }

        Tenant tenant = new Tenant();
        tenant.setName(request.tenantName());
        tenant.setSlug(request.tenantSlug());
        tenant = saveTenant(tenant);

        TenantContext.set(tenant.getId().toString());
        try {
            if (appUserRepository.findByTenantIdAndEmail(tenant.getId(), request.email()).isPresent()) {
                throw new AuthException(HttpStatus.CONFLICT, "Email already registered for this tenant");
            }

            AppUser user = new AppUser();
            user.setTenantId(tenant.getId());
            user.setEmail(request.email());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            user.setRole("ADMIN"); // first user of a tenant is always its admin
            user = saveUser(user);

            String token = jwtService.issueToken(user.getId().toString(), tenant.getId().toString(), user.getRole());
            return new AuthResponse(token, jwtService.expirationSeconds(),
                    tenant.getId().toString(), tenant.getSlug(),
                    user.getId().toString(), user.getEmail(), user.getRole());
        } finally {
            TenantContext.clear();
        }
    }

    public AuthResponse login(LoginRequest request) {
        // Deliberately the same generic message for "tenant not found",
        // "email not found" and "wrong password" -- distinguishing them
        // would let a caller enumerate valid tenant slugs / registered
        // emails.
        AuthException invalidCredentials = new AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials");

        Tenant tenant = tenantRepository.findBySlug(request.tenantSlug())
                .orElseThrow(() -> invalidCredentials);

        TenantContext.set(tenant.getId().toString());
        try {
            AppUser user = appUserRepository.findByTenantIdAndEmail(tenant.getId(), request.email())
                    .orElseThrow(() -> invalidCredentials);

            if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                throw invalidCredentials;
            }

            String token = jwtService.issueToken(user.getId().toString(), tenant.getId().toString(), user.getRole());
            return new AuthResponse(token, jwtService.expirationSeconds(),
                    tenant.getId().toString(), tenant.getSlug(),
                    user.getId().toString(), user.getEmail(), user.getRole());
        } finally {
            TenantContext.clear();
        }
    }

    private Tenant saveTenant(Tenant tenant) {
        try {
            return tenantRepository.save(tenant);
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(HttpStatus.CONFLICT, "Tenant slug already taken");
        }
    }

    private AppUser saveUser(AppUser user) {
        try {
            return appUserRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(HttpStatus.CONFLICT, "Email already registered for this tenant");
        }
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (isBlank(request.tenantName()) || isBlank(request.tenantSlug())
                || isBlank(request.email()) || request.password() == null || request.password().length() < 8) {
            throw new AuthException(HttpStatus.BAD_REQUEST,
                    "tenantName, tenantSlug, email are required and password must be at least 8 characters");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
