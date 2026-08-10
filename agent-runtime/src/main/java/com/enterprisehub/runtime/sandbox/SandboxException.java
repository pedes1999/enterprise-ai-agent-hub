package com.enterprisehub.runtime.sandbox;

/**
 * Thrown for failures in the sandbox layer itself (creation failed, sidecar
 * unreachable, command timed out) -- distinct from a command that ran and
 * simply exited non-zero, which is a normal CommandResult, not an
 * exception. Callers (AgentTool implementations) are expected to catch this
 * and turn it into a tool-result string the LLM can see and react to,
 * rather than letting it propagate as an unhandled error.
 */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
