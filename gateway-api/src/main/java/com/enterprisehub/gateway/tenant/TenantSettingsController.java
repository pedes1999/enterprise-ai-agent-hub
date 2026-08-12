package com.enterprisehub.gateway.tenant;

import com.enterprisehub.dto.TenantSettingsResponse;
import com.enterprisehub.dto.UpdateTenantSettingsRequest;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/tenant-settings")
@PreAuthorize("hasRole('ADMIN')")
public class TenantSettingsController {

    private final TenantSettingsService tenantSettingsService;

    public TenantSettingsController(TenantSettingsService tenantSettingsService) {
        this.tenantSettingsService = tenantSettingsService;
    }

    @GetMapping
    public ResponseEntity<TenantSettingsResponse> get(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(tenantSettingsService.get(UUID.fromString(principal.tenantId())));
    }

    @PutMapping
    public ResponseEntity<TenantSettingsResponse> update(@AuthenticationPrincipal PlatformPrincipal principal,
                                                           @RequestBody UpdateTenantSettingsRequest request) {
        return ResponseEntity.ok(tenantSettingsService.update(UUID.fromString(principal.tenantId()), request));
    }
}
