package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CreateVendorCredentialRequest;
import com.enterprisehub.dto.CredentialTestResult;
import com.enterprisehub.dto.ModelOption;
import com.enterprisehub.dto.TeamVendorCredentialSummary;
import com.enterprisehub.dto.VendorCredentialSummary;
import com.enterprisehub.dto.VendorCredentialTestRequest;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Every endpoint below (except the /team ones) manages the CALLER's own
 * credential -- ADMIN and DEVELOPER can each bring their own vendor key
 * (see V22__vendor_credentials_per_user.sql); there's no class-level
 * ADMIN-only gate anymore. READONLY is excluded the same way it's excluded
 * from triggering executions (AgentExecutionController) -- a role that
 * can't run agents has no use for a personal LLM key.
 *
 * /team is the one ADMIN-only surface: read-only visibility across every
 * teammate's credentials (who's connected, last used/validated) plus a
 * blind deactivate -- ADMIN never sees or touches the secret itself.
 */
@RestController
@RequestMapping("/vendor-credentials")
public class VendorCredentialController {

    private final VendorCredentialService vendorCredentialService;
    private final VendorCredentialTestService vendorCredentialTestService;
    private final VendorModelCatalogService vendorModelCatalogService;

    public VendorCredentialController(VendorCredentialService vendorCredentialService, VendorCredentialTestService vendorCredentialTestService,
                                       VendorModelCatalogService vendorModelCatalogService) {
        this.vendorCredentialService = vendorCredentialService;
        this.vendorCredentialTestService = vendorCredentialTestService;
        this.vendorModelCatalogService = vendorModelCatalogService;
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<VendorCredentialSummary> put(@AuthenticationPrincipal PlatformPrincipal principal,
                                                         @RequestBody CreateVendorCredentialRequest request) {
        var summary = vendorCredentialService.put(UUID.fromString(principal.tenantId()), UUID.fromString(principal.userId()), request);
        return ResponseEntity.ok(summary);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<List<VendorCredentialSummary>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(vendorCredentialService.list(UUID.fromString(principal.tenantId()), UUID.fromString(principal.userId())));
    }

    @DeleteMapping("/{provider}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal PlatformPrincipal principal,
                                        @PathVariable String provider) {
        vendorCredentialService.delete(UUID.fromString(principal.tenantId()), UUID.fromString(principal.userId()), provider);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<CredentialTestResult> test(@AuthenticationPrincipal PlatformPrincipal principal,
                                                       @RequestBody VendorCredentialTestRequest request) {
        var result = vendorCredentialTestService.test(UUID.fromString(principal.tenantId()), UUID.fromString(principal.userId()), request.provider());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{provider}/models")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<List<ModelOption>> listModels(@AuthenticationPrincipal PlatformPrincipal principal,
                                                          @PathVariable String provider) {
        return ResponseEntity.ok(vendorModelCatalogService.list(UUID.fromString(principal.tenantId()), UUID.fromString(principal.userId()), provider));
    }

    /**
     * "team" is a literal path segment ahead of "/{provider}/models" above --
     * Spring's routing resolves it by specificity, same non-issue as
     * AgentExecutionController's "usage" vs "{id}" (see its javadoc).
     */
    @GetMapping("/team")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<TeamVendorCredentialSummary>> listTeam(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(vendorCredentialService.listForTeam(UUID.fromString(principal.tenantId())));
    }

    @PostMapping("/team/{userId}/{provider}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateTeamCredential(@AuthenticationPrincipal PlatformPrincipal principal,
                                                           @PathVariable String userId,
                                                           @PathVariable String provider) {
        vendorCredentialService.deactivateForUser(UUID.fromString(principal.tenantId()), UUID.fromString(userId), provider);
        return ResponseEntity.noContent().build();
    }
}
