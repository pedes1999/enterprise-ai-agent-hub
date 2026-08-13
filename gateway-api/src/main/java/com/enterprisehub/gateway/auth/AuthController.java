package com.enterprisehub.gateway.auth;

import com.enterprisehub.dto.AuthResponse;
import com.enterprisehub.dto.ChangePasswordRequest;
import com.enterprisehub.dto.LoginRequest;
import com.enterprisehub.dto.RegisterRequest;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Under /auth/** (see SecurityConfig's permitAll) so it stays reachable
     * even while PasswordChangeRequiredFilter is blocking every other
     * endpoint for this caller -- but still requires a real JWT, checked
     * here rather than via @PreAuthorize since permitAll paths skip method
     * security entirely.
     */
    @PostMapping("/change-password")
    public ResponseEntity<AuthResponse> changePassword(@AuthenticationPrincipal PlatformPrincipal principal,
                                                          @RequestBody ChangePasswordRequest request) {
        if (principal == null) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return ResponseEntity.ok(authService.changePassword(
                UUID.fromString(principal.tenantId()), UUID.fromString(principal.userId()), request));
    }
}
