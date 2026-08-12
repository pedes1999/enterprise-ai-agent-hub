package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CreateToolCredentialRequest;
import com.enterprisehub.dto.CredentialTestResult;
import com.enterprisehub.dto.ToolCredentialSummary;
import com.enterprisehub.dto.ToolCredentialTestRequest;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tool-credentials")
@PreAuthorize("hasRole('ADMIN')")
public class ToolCredentialController {

    private final ToolCredentialService toolCredentialService;
    private final ToolCredentialTestService toolCredentialTestService;

    public ToolCredentialController(ToolCredentialService toolCredentialService, ToolCredentialTestService toolCredentialTestService) {
        this.toolCredentialService = toolCredentialService;
        this.toolCredentialTestService = toolCredentialTestService;
    }

    @PutMapping
    public ResponseEntity<ToolCredentialSummary> put(@AuthenticationPrincipal PlatformPrincipal principal,
                                                       @RequestBody CreateToolCredentialRequest request) {
        var summary = toolCredentialService.put(UUID.fromString(principal.tenantId()), request);
        return ResponseEntity.ok(summary);
    }

    @GetMapping
    public ResponseEntity<List<ToolCredentialSummary>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(toolCredentialService.list(UUID.fromString(principal.tenantId())));
    }

    @DeleteMapping("/{credentialKind}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal PlatformPrincipal principal,
                                        @PathVariable String credentialKind) {
        toolCredentialService.delete(UUID.fromString(principal.tenantId()), credentialKind);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    public ResponseEntity<CredentialTestResult> test(@AuthenticationPrincipal PlatformPrincipal principal,
                                                       @RequestBody ToolCredentialTestRequest request) {
        var result = toolCredentialTestService.test(UUID.fromString(principal.tenantId()), request.credentialKind());
        return ResponseEntity.ok(result);
    }
}
