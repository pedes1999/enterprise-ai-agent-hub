package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.gateway.agent.AgentExecutionService;
import com.enterprisehub.gateway.agent.tools.DelegateToAgentTool;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import org.springframework.stereotype.Component;

@Component
public class DelegateToAgentToolFactory implements ToolFactory {

    private final AgentExecutionService executionService;

    public DelegateToAgentToolFactory(AgentExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public String toolName() {
        return "delegate_to_agent";
    }

    @Override
    public String category() {
        return "orchestration";
    }

    @Override
    public AgentTool create(SandboxSession session, ToolExecutionListener listener, CredentialResolver credentialResolver) {
        return new DelegateToAgentTool(executionService);
    }
}
