package com.enterprisehub.runtime.credential;

import java.util.Map;

/**
 * Resolves the minimum scoped credential a specific tool invocation needs,
 * for a specific tenant -- e.g. a future git tool asks for "GIT" and gets
 * back that tenant's decrypted repo token, never anyone else's and never
 * more than that one credential. agent-runtime depends on this interface
 * only; gateway-api provides the real implementation, backed by whatever
 * table ends up storing repo/tool credentials -- that storage schema is a
 * gateway-api concern, out of scope here, mirroring how VendorCredentialService
 * already decrypts LLM provider credentials without agent-core knowing how
 * that storage works.
 */
public interface CredentialResolver {

    /**
     * Returns environment-variable-shaped credentials for the given tenant
     * and credential kind (e.g. "GIT"). Returns an empty map if the tenant
     * has none configured for that kind -- callers decide whether that's an
     * error (a git tool with no git credential configured should fail
     * clearly) or acceptable (a tool that only needs credentials
     * conditionally).
     */
    Map<String, String> resolve(String tenantId, String credentialKind);
}
