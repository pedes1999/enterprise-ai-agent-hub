package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import com.enterprisehub.runtime.tools.GitCloneTool;
import org.springframework.stereotype.Component;

@Component
public class GitCloneToolFactory implements ToolFactory {

    @Override
    public String toolName() {
        return "git_clone";
    }

    @Override
    public String category() {
        return "git";
    }

    @Override
    public AgentTool create(SandboxSession session, CredentialResolver credentialResolver, ToolCreationContext toolContext) {
        return new GitCloneTool(session, credentialResolver);
    }
}
