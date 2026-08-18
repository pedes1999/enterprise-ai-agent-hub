package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import com.enterprisehub.runtime.tools.OpenPullRequestTool;
import org.springframework.stereotype.Component;

@Component
public class OpenPullRequestToolFactory implements ToolFactory {

    @Override
    public String toolName() {
        return "open_pull_request";
    }

    @Override
    public String category() {
        return "git";
    }

    @Override
    public AgentTool create(SandboxSession session, ToolExecutionListener listener, CredentialResolver credentialResolver,
                             ToolCreationContext toolContext) {
        return new OpenPullRequestTool(session, listener, credentialResolver);
    }
}
