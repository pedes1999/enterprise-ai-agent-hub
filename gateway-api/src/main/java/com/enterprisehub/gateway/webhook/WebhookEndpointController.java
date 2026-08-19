package com.enterprisehub.gateway.webhook;

import com.enterprisehub.dto.CreateWebhookEndpointRequest;
import com.enterprisehub.dto.WebhookEndpointCreatedResponse;
import com.enterprisehub.dto.WebhookEndpointSummary;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * ADMIN-only, on exactly the reasoning ApiKeyController already documents:
 * creating one of these mints a standing credential that runs agents against
 * real repositories unattended. A DEVELOPER can trigger runs interactively
 * but shouldn't be able to leave something behind that keeps doing it.
 *
 * There is a second reason here specifically -- an endpoint records whose
 * vendor credential its runs spend (see WebhookEndpoint.runAsUserId), so
 * creating one is a decision about someone's billed API usage.
 */
@RestController
@RequestMapping("/webhook-endpoints")
@PreAuthorize("hasRole('ADMIN')")
public class WebhookEndpointController {

    private final WebhookEndpointService endpointService;

    public WebhookEndpointController(WebhookEndpointService endpointService) {
        this.endpointService = endpointService;
    }

    /** 201 with the secret in the body -- the only time it is ever returned. */
    @PostMapping
    public ResponseEntity<WebhookEndpointCreatedResponse> create(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                    @RequestBody CreateWebhookEndpointRequest request) {
        WebhookEndpointCreatedResponse created = endpointService.create(
                UUID.fromString(principal.tenantId()),
                UUID.fromString(principal.userId()),
                request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<WebhookEndpointSummary>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(endpointService.list(UUID.fromString(principal.tenantId())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal PlatformPrincipal principal, @PathVariable UUID id) {
        endpointService.deactivate(UUID.fromString(principal.tenantId()), id);
        return ResponseEntity.noContent().build();
    }
}
