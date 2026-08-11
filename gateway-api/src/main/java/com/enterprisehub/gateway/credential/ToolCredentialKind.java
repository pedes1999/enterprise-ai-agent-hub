package com.enterprisehub.gateway.credential;

import java.util.Arrays;
import java.util.Optional;

/** Mirrors agent-runtime's expectations of which credentialKind strings a CredentialResolver may be asked for. */
public enum ToolCredentialKind {
    GIT,
    /** A PAT with repo/pull-request scope, used by OpenPullRequestTool to push a branch and open a PR via the GitHub REST API. */
    GITHUB;

    public static Optional<ToolCredentialKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(kind -> kind.name().equalsIgnoreCase(value))
                .findFirst();
    }
}
