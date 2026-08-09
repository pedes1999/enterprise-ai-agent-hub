package com.enterprisehub.gateway.apikey;

import com.enterprisehub.dto.ApiKeyCreatedResponse;
import com.enterprisehub.dto.ApiKeySummary;
import com.enterprisehub.dto.CreateApiKeyRequest;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    public ResponseEntity<ApiKeyCreatedResponse> create(@AuthenticationPrincipal PlatformPrincipal principal,
                                                          @RequestBody CreateApiKeyRequest request) {
        var created = apiKeyService.create(UUID.fromString(principal.tenantId()), request.label());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<ApiKeySummary>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(apiKeyService.list(UUID.fromString(principal.tenantId())));
    }

    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal PlatformPrincipal principal,
                                        @PathVariable UUID keyId) {
        apiKeyService.revoke(UUID.fromString(principal.tenantId()), keyId);
        return ResponseEntity.noContent().build();
    }
}
