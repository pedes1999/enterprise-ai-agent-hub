package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import com.enterprisehub.runtime.tools.WriteFileTool;
import org.springframework.stereotype.Component;

@Component
public class WriteFileToolFactory implements ToolFactory {

    @Override
    public String toolName() {
        return "write_file";
    }

    @Override
    public String category() {
        return "filesystem";
    }

    @Override
    public AgentTool create(SandboxSession session, ToolExecutionListener listener, CredentialResolver credentialResolver,
                             ToolCreationContext toolContext) {
        return new WriteFileTool(session, listener);
    }
}
