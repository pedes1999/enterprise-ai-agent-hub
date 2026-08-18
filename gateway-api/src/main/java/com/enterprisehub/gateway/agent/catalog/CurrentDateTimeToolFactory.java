package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.gateway.agent.tools.CurrentDateTimeTool;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import org.springframework.stereotype.Component;

@Component
public class CurrentDateTimeToolFactory implements ToolFactory {

    @Override
    public String toolName() {
        return "get_current_date_time";
    }

    @Override
    public String category() {
        return "utility";
    }

    @Override
    public AgentTool create(SandboxSession session, ToolExecutionListener listener, CredentialResolver credentialResolver,
                             ToolCreationContext toolContext) {
        return new CurrentDateTimeTool();
    }
}
