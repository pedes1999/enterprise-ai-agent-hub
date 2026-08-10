package com.enterprisehub.runtime.sandbox;

import java.time.Duration;

/**
 * Makes one real sandbox last for an entire agent execution instead of a
 * single tool call. Every AgentTool built on AbstractSandboxedTool still
 * calls {@code sandboxClient.create(spec)} / {@code destroy(handle)} on its
 * own, exactly as if it owned a private sandbox (see SandboxRunner) --
 * SandboxSession is a decorator that intercepts those calls: the first
 * {@code create()} from ANY tool actually provisions a sandbox (using
 * THIS session's own spec, not whatever the calling tool passed in --
 * see below for why), every subsequent {@code create()} from any other
 * tool call in the same execution returns that same cached handle, and
 * {@code destroy()} is a no-op until the owner calls {@link #endSession()}.
 * No changes needed to RunShellCommandTool, GitCloneTool, or any future
 * sandboxed tool for this to work.
 *
 * Why the session's own spec, not the calling tool's: env vars (including
 * injected credentials) are only ever set at E2B sandbox creation time --
 * there's no way to add a new one to an already-running sandbox for a
 * later command. If GitCloneTool happened to run AFTER RunShellCommandTool
 * in the same execution, and the sandbox had already been created from
 * RunShellCommandTool's (credential-less) spec, GitCloneTool's git
 * credential would silently never make it into the sandbox. The owner
 * (AgentPromptRunner) resolves every credential kind a tool in this run
 * might need up front and builds ONE spec with all of them before any tool
 * runs -- see its javadoc.
 *
 * Not thread-safe across concurrent tool calls (today's ToolCallingChatEngine
 * only ever runs one tool call at a time per execution, so this hasn't
 * needed to be).
 */
public final class SandboxSession implements SandboxClient {

    private final SandboxClient delegate;
    private final SandboxSpec sessionSpec;
    private SandboxHandle handle;

    public SandboxSession(SandboxClient delegate, SandboxSpec sessionSpec) {
        this.delegate = delegate;
        this.sessionSpec = sessionSpec;
    }

    @Override
    public SandboxHandle create(SandboxSpec ignoredPerCallSpec) {
        if (handle == null) {
            handle = delegate.create(sessionSpec);
        }
        return handle;
    }

    @Override
    public CommandResult runCommand(SandboxHandle handle, String command, Duration timeout) {
        return delegate.runCommand(handle, command, timeout);
    }

    @Override
    public void writeFile(SandboxHandle handle, String path, byte[] content) {
        delegate.writeFile(handle, path, content);
    }

    @Override
    public byte[] readFile(SandboxHandle handle, String path) {
        return delegate.readFile(handle, path);
    }

    /** No-op by design -- see class javadoc. Individual tool calls must not be able to end the shared session early. */
    @Override
    public void destroy(SandboxHandle handle) {
    }

    /**
     * Actually destroys the underlying sandbox, if one was ever created
     * (a run where no tool happened to touch the sandbox never provisions
     * one at all). Idempotent and never throws, same contract as
     * SandboxClient.destroy() itself. The owner (AgentPromptRunner) calls
     * this exactly once, in a finally block, after the whole execution's
     * tool-calling loop has finished -- success or failure.
     */
    public void endSession() {
        if (handle != null) {
            delegate.destroy(handle);
            handle = null;
        }
    }
}
