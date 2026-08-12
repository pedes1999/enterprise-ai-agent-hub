package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CreateVendorCredentialRequest;
import com.enterprisehub.dto.CredentialTestResult;
import com.enterprisehub.dto.VendorCredentialSummary;
import com.enterprisehub.dto.VendorCredentialTestRequest;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/vendor-credentials")
@PreAuthorize("hasRole('ADMIN')")
public class VendorCredentialController {

    private final VendorCredentialService vendorCredentialService;
    private final VendorCredentialTestService vendorCredentialTestService;

    public VendorCredentialController(VendorCredentialService vendorCredentialService, VendorCredentialTestService vendorCredentialTestService) {
        this.vendorCredentialService = vendorCredentialService;
        this.vendorCredentialTestService = vendorCredentialTestService;
    }

    @PutMapping
    public ResponseEntity<VendorCredentialSummary> put(@AuthenticationPrincipal PlatformPrincipal principal,
                                                         @RequestBody CreateVendorCredentialRequest request) {
        var summary = vendorCredentialService.put(UUID.fromString(principal.tenantId()), request);
        return ResponseEntity.ok(summary);
    }

    @GetMapping
    public ResponseEntity<List<VendorCredentialSummary>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(vendorCredentialService.list(UUID.fromString(principal.tenantId())));
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal PlatformPrincipal principal,
                                        @PathVariable String provider) {
        vendorCredentialService.delete(UUID.fromString(principal.tenantId()), provider);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    public ResponseEntity<CredentialTestResult> test(@AuthenticationPrincipal PlatformPrincipal principal,
                                                       @RequestBody VendorCredentialTestRequest request) {
        var result = vendorCredentialTestService.test(UUID.fromString(principal.tenantId()), request.provider());
        return ResponseEntity.ok(result);
    }
}
