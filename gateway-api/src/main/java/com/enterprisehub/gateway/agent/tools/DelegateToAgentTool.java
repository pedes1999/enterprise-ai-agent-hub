package com.enterprisehub.gateway.agent.tools;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.gateway.agent.AgentExecutionService;
import com.enterprisehub.gateway.agent.EnqueueExecutionCommand;
import com.enterprisehub.gateway.entity.AgentExecution;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The first (and so far only) tool a "planner"-shaped AgentDefinition uses
 * (see V25__agent_execution_parent_and_planner.sql) -- queues a new,
 * independently-tracked agent_executions row for a named agent, tagged with
 * this execution's own id as parentExecutionId, and returns immediately.
 *
 * Deliberately fire-and-forget, NOT a blocking wait for the child to
 * finish: AgentJobWorker polls one QUEUED job at a time via
 * @Scheduled(fixedDelay=...), which by default runs on a single scheduler
 * thread. If this tool blocked the calling thread until its child
 * completed, and that calling thread IS the worker thread that would
 * otherwise claim and run the child, the execution would deadlock forever
 * waiting on itself. Reviewing a child's actual result (a genuine
 * Planner/Coder/Reviewer pipeline) is intentionally left for a later pass --
 * this proves delegation and independent tracking work end to end without
 * that risk.
 *
 * Not sandboxed -- ignores the SandboxSession/CredentialResolver
 * DelegateToAgentToolFactory.create() receives, same as
 * CurrentDateTimeToolFactory does for the params it doesn't need.
 */
public class DelegateToAgentTool implements AgentTool {

    private final AgentExecutionService executionService;

    public DelegateToAgentTool(AgentExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public String name() {
        return "delegate_to_agent";
    }

    @Override
    public String description() {
        return "Queues a new, separately-tracked execution of a named agent (by its slug, e.g. "
                + "'ticket-resolver' or 'test-fixer') to handle one stage of work. Returns immediately "
                + "once queued -- it does NOT wait for the delegated execution to finish and does NOT "
                + "report its eventual result.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of(
                "agentSlug", "The slug of the agent to delegate to, e.g. 'ticket-resolver' or 'test-fixer'.",
                "prompt", "The instructions for the delegated agent -- written so it makes sense on its own, not just a repeat of your own instructions.",
                "repositoryUrl", "Optional -- the git repository URL, if this stage of work needs one.");
    }

    @Override
    public Set<String> optionalParameterNames() {
        return Set.of("repositoryUrl");
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        UUID tenantId = UUID.fromString(context.tenantId());
        UUID parentExecutionId = UUID.fromString(context.executionId());
        String agentSlug = arguments.get("agentSlug");
        String prompt = arguments.get("prompt");
        String repositoryUrl = arguments.get("repositoryUrl");

        // The child needs the SAME triggering user as the parent so its own
        // credential resolution works identically (per-user vendor
        // credentials, see AgentPromptRunner.resolveApiKey / V22-V23) --
        // looked up from the parent's own persisted row rather than adding a
        // third field to the widely-shared ToolExecutionContext record just
        // for this one caller.
        UUID triggeredBy = executionService.findForTenant(tenantId, parentExecutionId)
                .map(AgentExecution::getTriggeredBy)
                .orElse(null);

        try {
            AgentExecution child = executionService.enqueue(
                    EnqueueExecutionCommand.forAgent(tenantId, agentSlug)
                            .prompt(prompt)
                            .repositoryUrl(repositoryUrl)
                            .triggeredBy(triggeredBy)
                            .parentExecutionId(parentExecutionId)
                            .build());
            return "Delegated to a new '" + agentSlug + "' execution (id=" + child.getId() + "), queued. "
                    + "This does not report that execution's eventual result.";
        } catch (RuntimeException e) {
            // Same isolation contract as every other tool -- ToolCallingChatEngine.executeTool()
            // catches broadly too, but a specific message here (unknown agent slug, missing
            // required input on the target agent, tenant concurrency cap) is more useful to the
            // model than a generic one from that outer catch.
            return "Error delegating to agent '" + agentSlug + "': " + e.getMessage();
        }
    }
}
