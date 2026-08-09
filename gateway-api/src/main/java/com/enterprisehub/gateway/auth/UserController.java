package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.CreateUserRequest;
import com.enterprisehub.dto.UpdateUserRoleRequest;
import com.enterprisehub.dto.UserSummary;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * ADMIN-only: this is how DEVELOPER/READONLY users ever come to exist --
 * self-registration (AuthController) always creates a tenant's first ADMIN,
 * there's no public sign-up path into an existing tenant.
 */
@RestController
@RequestMapping("/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserSummary> create(@AuthenticationPrincipal PlatformPrincipal principal,
                                               @RequestBody CreateUserRequest request) {
        var created = userService.create(UUID.fromString(principal.tenantId()), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<UserSummary>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(userService.list(UUID.fromString(principal.tenantId())));
    }

    @PatchMapping("/{userId}/role")
    public ResponseEntity<UserSummary> updateRole(@AuthenticationPrincipal PlatformPrincipal principal,
                                                    @PathVariable UUID userId,
                                                    @RequestBody UpdateUserRoleRequest request) {
        var updated = userService.updateRole(UUID.fromString(principal.tenantId()), userId, request.role());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal PlatformPrincipal principal,
                                        @PathVariable UUID userId) {
        userService.delete(UUID.fromString(principal.tenantId()), userId);
        return ResponseEntity.noContent().build();
    }
}
