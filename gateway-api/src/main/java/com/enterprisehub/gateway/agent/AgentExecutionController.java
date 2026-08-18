package com.enterprisehub.gateway.agent;

import com.enterprisehub.dto.AgentDefinitionDetail;
import com.enterprisehub.dto.AgentDefinitionSummary;
import com.enterprisehub.dto.AgentExecutionAccepted;
import com.enterprisehub.dto.AgentExecutionStatusResponse;
import com.enterprisehub.dto.AgentTokenUsageStats;
import com.enterprisehub.dto.ExecutionUsage;
import com.enterprisehub.dto.ToolExecutionRecord;
import com.enterprisehub.dto.TriggerAgentExecutionRequest;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The real (durable, async) agent execution model, as opposed to
 * AgentPingController's synchronous spike endpoints. Same role gate
 * (ADMIN, DEVELOPER can trigger; READONLY cannot -- see the role matrix in
 * USER_STORIES.md). POST returns immediately with a QUEUED execution id;
 * AgentJobWorker picks it up separately. GET .../executions/{id} is how a
 * caller finds out what happened -- there is deliberately no push/webhook
 * notification yet. GET .../definitions is the browsable catalog a caller
 * picks an agentSlug from -- see AgentDefinition.
 */
@RestController
@RequestMapping("/agents")
public class AgentExecutionController {

    private final AgentExecutionService executionService;
    private final AgentDefinitionService agentDefinitionService;

    public AgentExecutionController(AgentExecutionService executionService, AgentDefinitionService agentDefinitionService) {
        this.executionService = executionService;
        this.agentDefinitionService = agentDefinitionService;
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<AgentExecutionAccepted> execute(@AuthenticationPrincipal PlatformPrincipal principal,
                                                            @RequestBody TriggerAgentExecutionRequest request) {
        // No ad hoc "prompt is required" check here anymore -- whether prompt,
        // repositoryUrl, or a specific inputParameters key is required is
        // entirely a property of the resolved AgentDefinition now (see
        // AgentExecutionService.validateRequiredInputs()).
        String agentSlug = (request.agentSlug() == null || request.agentSlug().isBlank())
                ? AgentPromptRunner.DEFAULT_AGENT_SLUG : request.agentSlug();
        AgentExecution execution = executionService.enqueue(
                EnqueueExecutionCommand.forAgent(UUID.fromString(principal.tenantId()), agentSlug)
                        .prompt(request.prompt())
                        .repository(request.repositoryUrl(), request.repositoryBranch())
                        .inputParameters(request.inputParameters())
                        .maxTokens(request.maxTokens())
                        .triggeredBy(UUID.fromString(principal.userId()))
                        .build());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AgentExecutionAccepted(execution.getId(), execution.getStatus()));
    }

    /**
     * "3 / 5 executions running" -- lets a caller see remaining capacity
     * before submitting a trigger request. A literal path segment
     * ("usage"), so Spring's routing resolves it ahead of the {id}
     * variable below for any request to exactly this URL -- path
     * specificity, not declaration order, is what makes that safe.
     */
    @GetMapping("/executions/usage")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','READONLY')")
    public ResponseEntity<ExecutionUsage> getUsage(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(executionService.getUsage(UUID.fromString(principal.tenantId())));
    }

    /**
     * "Past runs of this agent used ~X-Y tokens" -- a concrete reference
     * point for the trigger form's maxTokens override field, instead of a
     * blind guess. A literal path segment ahead of {id} for the same
     * routing-specificity reason getUsage() above already documents.
     */
    @GetMapping("/executions/token-usage-stats")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','READONLY')")
    public ResponseEntity<AgentTokenUsageStats> getTokenUsageStats(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                      @RequestParam String agentSlug) {
        return ResponseEntity.ok(executionService.getTokenUsageStats(UUID.fromString(principal.tenantId()), agentSlug));
    }

    @GetMapping("/executions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','READONLY')")
    public ResponseEntity<AgentExecutionStatusResponse> getExecution(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                       @PathVariable UUID id) {
        AgentExecution execution = executionService.findForTenant(UUID.fromString(principal.tenantId()), id)
                .orElseThrow(() -> new AgentException(HttpStatus.NOT_FOUND, "No execution with id " + id));
        return ResponseEntity.ok(toResponse(execution));
    }

    /**
     * Tenant-scoped, paginated (standard Spring Pageable -- ?page=0&size=20&sort=createdAt,desc),
     * optional status filter. Same 3-role read access as getExecution().
     *
     * Returns PagedModel, not a raw Page -- Page's own JSON shape depends
     * on Jackson modules being registered in the serving ApplicationContext
     * (Spring Boot's docs call raw Page serialization out as unstable
     * across versions for exactly this reason) and PagedModel doesn't
     * require anything extra to serialize predictably.
     */
    @GetMapping("/executions")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','READONLY')")
    public ResponseEntity<PagedModel<AgentExecutionStatusResponse>> listExecutions(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                                     @RequestParam(required = false) String status,
                                                                                     Pageable pageable) {
        Page<AgentExecution> page = executionService.list(UUID.fromString(principal.tenantId()), status, pageable);
        return ResponseEntity.ok(new PagedModel<>(page.map(this::toResponse)));
    }

    /** The ordered tool-call trace for one execution -- what a skeptical teammate opens to verify what an agent actually did. */
    @GetMapping("/executions/{id}/tool-executions")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','READONLY')")
    public ResponseEntity<List<ToolExecutionRecord>> getToolExecutions(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                         @PathVariable UUID id) {
        return ResponseEntity.ok(executionService.getToolExecutions(UUID.fromString(principal.tenantId()), id));
    }

    /** Every execution delegate_to_agent queued from this one (see V25) -- empty, not 404, if this execution never delegated anything. */
    @GetMapping("/executions/{id}/children")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','READONLY')")
    public ResponseEntity<List<AgentExecutionStatusResponse>> getChildren(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                            @PathVariable UUID id) {
        List<AgentExecution> children = executionService.getChildren(UUID.fromString(principal.tenantId()), id);
        return ResponseEntity.ok(children.stream().map(this::toResponse).toList());
    }

    @GetMapping("/definitions")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','READONLY')")
    public ResponseEntity<List<AgentDefinitionSummary>> listDefinitions() {
        return ResponseEntity.ok(agentDefinitionService.listActive());
    }

    /** Full, read-only configuration for one definition -- "view configuration" on a catalog card, not an edit form (see AgentDefinition's javadoc). */
    @GetMapping("/definitions/{slug}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','READONLY')")
    public ResponseEntity<AgentDefinitionDetail> getDefinition(@PathVariable String slug) {
        return ResponseEntity.ok(agentDefinitionService.getDetail(slug));
    }

    private AgentExecutionStatusResponse toResponse(AgentExecution execution) {
        return new AgentExecutionStatusResponse(
                execution.getId(), execution.getStatus(), execution.getLlmProvider(), execution.getAgentType(), execution.getPrompt(),
                execution.getRepositoryUrl(), execution.getRepositoryBranch(), executionService.deserializeInputParameters(execution),
                execution.getReply(), execution.getToolWasUsed(), execution.getErrorMessage(),
                execution.getCreatedAt(), execution.getStartedAt(), execution.getCompletedAt(),
                execution.getInputTokens(), execution.getOutputTokens(), execution.getTotalTokens(),
                execution.getMaxTokensOverride(), execution.getParentExecutionId());
    }
}
