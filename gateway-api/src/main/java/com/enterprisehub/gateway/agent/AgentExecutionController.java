package com.enterprisehub.gateway.agent;

import com.enterprisehub.dto.AgentExecutionAccepted;
import com.enterprisehub.dto.AgentExecutionStatusResponse;
import com.enterprisehub.dto.TriggerAgentExecutionRequest;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The real (durable, async) agent execution model, as opposed to
 * AgentPingController's synchronous spike endpoints. Same role gate
 * (ADMIN, DEVELOPER can trigger; READONLY cannot -- see the role matrix in
 * USER_STORIES.md). POST returns immediately with a QUEUED execution id;
 * AgentJobWorker picks it up separately. GET is how a caller finds out
 * what happened -- there is deliberately no push/webhook notification yet.
 */
@RestController
@RequestMapping("/agents")
public class AgentExecutionController {

    private final AgentExecutionService executionService;

    public AgentExecutionController(AgentExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<AgentExecutionAccepted> execute(@AuthenticationPrincipal PlatformPrincipal principal,
                                                            @RequestBody TriggerAgentExecutionRequest request) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "prompt is required");
        }
        AgentExecution execution = executionService.enqueue(UUID.fromString(principal.tenantId()), request.prompt());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AgentExecutionAccepted(execution.getId(), execution.getStatus()));
    }

    @GetMapping("/executions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','READONLY')")
    public ResponseEntity<AgentExecutionStatusResponse> getExecution(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                       @PathVariable UUID id) {
        AgentExecution execution = executionService.findForTenant(UUID.fromString(principal.tenantId()), id)
                .orElseThrow(() -> new AgentException(HttpStatus.NOT_FOUND, "No execution with id " + id));
        return ResponseEntity.ok(toResponse(execution));
    }

    private AgentExecutionStatusResponse toResponse(AgentExecution execution) {
        return new AgentExecutionStatusResponse(
                execution.getId(), execution.getStatus(), execution.getLlmProvider(), execution.getPrompt(),
                execution.getReply(), execution.getToolWasUsed(), execution.getErrorMessage(),
                execution.getCreatedAt(), execution.getStartedAt(), execution.getCompletedAt());
    }
}
