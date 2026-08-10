package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;

/**
 * The seam that lets ToolCatalog stay a plain, self-assembling Spring
 * registry instead of a switch statement that has to change every time a
 * tool is added -- one @Component ToolFactory bean per AgentTool. At
 * "hundreds of tools" scale, adding tool #101 means adding one new
 * AgentTool + one new ToolFactory bean; nothing else in gateway-api
 * changes.
 *
 * create() always receives the SAME SandboxSession for every tool in one
 * execution (see AgentPromptRunner) -- a factory doesn't get to decide
 * whether its tool shares state with others, that's SandboxSession's job.
 * A tool that doesn't need sandboxing at all (e.g. CurrentDateTimeTool)
 * just ignores the parameters it doesn't need.
 */
public interface ToolFactory {

    /** Must match the AgentTool's own name() -- this is what an AgentDefinition's tool_names list references. */
    String toolName();

    /** Free-text grouping for a future catalog browsing UI (e.g. "utility", "git", "filesystem", "shell"). Not enforced. */
    String category();

    AgentTool create(SandboxSession session, ToolExecutionListener listener, CredentialResolver credentialResolver);
}
