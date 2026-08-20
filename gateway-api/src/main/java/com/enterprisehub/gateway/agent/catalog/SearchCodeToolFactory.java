package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import com.enterprisehub.runtime.tools.SearchCodeTool;
import org.springframework.stereotype.Component;

@Component
public class SearchCodeToolFactory implements ToolFactory {

    @Override
    public String toolName() {
        return "search_code";
    }

    @Override
    public String category() {
        return "filesystem";
    }

    @Override
    public AgentTool create(SandboxSession session, CredentialResolver credentialResolver, ToolCreationContext toolContext) {
        return new SearchCodeTool(session);
    }
}
