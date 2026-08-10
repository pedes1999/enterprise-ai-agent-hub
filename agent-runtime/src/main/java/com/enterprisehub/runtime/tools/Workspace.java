package com.enterprisehub.runtime.tools;

/**
 * The shared directory every sandboxed tool in one execution operates
 * against once a SandboxSession makes the sandbox itself persist across
 * multiple tool calls (see SandboxSession's javadoc) -- GitCloneTool clones
 * here, RunShellCommandTool runs from here, ReadFileTool/WriteFileTool
 * resolve relative paths against here. Before SandboxSession existed, each
 * tool got its own throwaway sandbox, so this only ever mattered to
 * GitCloneTool; now that a run can chain clone -> read -> edit -> run
 * tests, every tool needs to agree on the same location.
 *
 * /tmp/workspace (not /workspace) because /workspace at a fresh E2B
 * sandbox's filesystem root isn't writable by its default user -- see
 * GitCloneTool's original bugfix, discovered via live testing.
 */
final class Workspace {

    static final String ROOT = "/tmp/workspace/repo";

    private Workspace() {
    }
}
