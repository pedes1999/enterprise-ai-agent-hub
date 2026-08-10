package com.enterprisehub.runtime.tools;

/**
 * Resolves a tool argument (a path the LLM supplied) against Workspace.ROOT,
 * rejecting anything that could escape it. The LLM's input here is not
 * trusted any more than a repository URL is (see GitCloneTool's argument
 * validation) -- a path like "../../etc/passwd" must never be allowed to
 * resolve outside the workspace, even though the sandbox itself is already
 * isolated per execution (defense in depth, not the only layer).
 */
final class WorkspacePath {

    private WorkspacePath() {
    }

    static String resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path argument is required");
        }
        if (relativePath.startsWith("/")) {
            throw new IllegalArgumentException("path must be relative to the workspace root, not absolute");
        }
        for (String segment : relativePath.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("path must not contain '..' segments");
            }
        }
        return Workspace.ROOT + "/" + relativePath;
    }
}
