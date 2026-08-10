package com.enterprisehub.gateway.agent.catalog;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.credential.CredentialResolver;
import com.enterprisehub.runtime.sandbox.SandboxSession;
import com.enterprisehub.runtime.tools.RunShellCommandTool;
import org.springframework.stereotype.Component;

@Component
public class RunShellCommandToolFactory implements ToolFactory {

    @Override
    public String toolName() {
        return "run_shell_command";
    }

    @Override
    public String category() {
        return "shell";
    }

    @Override
    public AgentTool create(SandboxSession session, ToolExecutionListener listener, CredentialResolver credentialResolver) {
        return new RunShellCommandTool(session, listener);
    }
}
