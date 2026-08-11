package com.enterprisehub.runtime.tools;

/**
 * POSIX single-quote shell escaping, shared by every tool that interpolates
 * LLM-supplied (untrusted) values into a shell command string -- wrap in
 * '...', escape embedded single quotes as '\''. Extracted once GitCloneTool
 * and WriteFileTool both needed the identical logic; OpenPullRequestTool is
 * the third user.
 */
final class ShellQuoting {

    private ShellQuoting() {
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
