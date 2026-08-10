package com.enterprisehub.runtime.sandbox;

import java.time.Duration;

/**
 * The only thing agent-runtime's public API knows about sandboxed
 * execution. No AgentTool implementation, and nothing in agent-core, ever
 * sees E2B (or whatever backs this later) directly -- mirrors how
 * agent-core hides LangChain4j from everything above it (see
 * AgentTool's own javadoc for that precedent).
 *
 * First implementation (SandboxClientHttpImpl, package
 * com.enterprisehub.runtime.sandbox.http) calls out to a small internal
 * sidecar service that wraps E2B's official SDK -- a deliberate, temporary
 * choice, not a compromise being hidden:
 *
 *   - E2B has no official Java SDK (Python/JS only). Their data-plane
 *     protocol (gRPC to the in-VM `envd` daemon; proto files live in E2B's
 *     open-source `infra` repo under packages/envd/spec/) is real and
 *     technically public, but it's internal plumbing shared only through
 *     their official SDKs -- not a documented, versioned external contract.
 *     Hand-rolling a Java gRPC client against it risks a silent break with
 *     no changelog pointing at us if E2B changes it.
 *   - We haven't yet validated the sandbox model operationally (timeout
 *     behavior, credential injection, output limits under real load).
 *     Committing to that protocol risk now, for a vendor we might still
 *     swap, is premature.
 *
 * A second implementation calling E2B's REST API directly (control plane)
 * plus a hand-generated gRPC client against their envd proto files (data
 * plane, via grpc-java + protobuf-maven-plugin) is a valid future path if
 * the sidecar's operational cost (an extra deployed service, an extra
 * language in the stack) outweighs its benefit later. That's meant to be a
 * config-swappable SandboxClient implementation choice when/if it's worth
 * doing, not a rewrite -- which is exactly why this interface exists as the
 * boundary it is.
 */
public interface SandboxClient {

    /** Provisions a fresh, empty sandbox. Caller owns its lifecycle from here -- see SandboxRunner for a safer wrapper. */
    SandboxHandle create(SandboxSpec spec);

    CommandResult runCommand(SandboxHandle handle, String command, Duration timeout);

    void writeFile(SandboxHandle handle, String path, byte[] content);

    byte[] readFile(SandboxHandle handle, String path);

    /** Idempotent -- safe to call on an already-destroyed or failed handle. Never throws. */
    void destroy(SandboxHandle handle);
}
