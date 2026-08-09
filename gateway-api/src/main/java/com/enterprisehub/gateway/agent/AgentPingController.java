package com.enterprisehub.gateway.agent;

import com.enterprisehub.dto.AgentPingRequest;
import com.enterprisehub.dto.AgentPingResponse;
import com.enterprisehub.dto.AgentToolPingResponse;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ADMIN + DEVELOPER per the role matrix ("trigger agents" is not
 * ADMIN-only, unlike credentials/keys/users) -- READONLY cannot invoke this.
 */
@RestController
@RequestMapping("/agents")
@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
public class AgentPingController {

    private final AgentPingService agentPingService;

    public AgentPingController(AgentPingService agentPingService) {
        this.agentPingService = agentPingService;
    }

    @PostMapping("/ping")
    public ResponseEntity<AgentPingResponse> ping(@AuthenticationPrincipal PlatformPrincipal principal,
                                                    @RequestBody AgentPingRequest request) {
        var response = agentPingService.ping(UUID.fromString(principal.tenantId()), request.prompt());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/ping-with-tools")
    public ResponseEntity<AgentToolPingResponse> pingWithTools(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                  @RequestBody AgentPingRequest request) {
        var response = agentPingService.pingWithTools(UUID.fromString(principal.tenantId()), request.prompt());
        return ResponseEntity.ok(response);
    }
}
