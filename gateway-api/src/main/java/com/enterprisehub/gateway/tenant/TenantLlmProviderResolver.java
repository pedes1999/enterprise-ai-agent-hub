package com.enterprisehub.gateway.tenant;

import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.entity.Tenant;
import com.enterprisehub.gateway.repository.TenantRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A tenant with active credentials for more than one provider (e.g.
 * ANTHROPIC and LOCAL) needs a way to say which one its agent executions
 * actually use -- app.llm.provider alone is a single server-wide default,
 * see LlmProperties' javadoc. Tenant.preferredLlmProvider is that
 * per-tenant override: null/unset falls back to the server default,
 * exactly the previous (pre-override) behavior.
 *
 * Deliberately does NOT validate that the tenant still has an active
 * credential for whatever preferredLlmProvider says -- that check belongs
 * to whoever SETS the preference (see TenantSettingsService), not to every
 * caller resolving it before a run. A credential removed after the fact
 * still fails with the usual actionable "No active X credential" error
 * from AgentPromptRunner/AgentPingService, just like it always has.
 */
@Component
public class TenantLlmProviderResolver {

    private final TenantRepository tenantRepository;
    private final LlmProperties llmProperties;

    public TenantLlmProviderResolver(TenantRepository tenantRepository, LlmProperties llmProperties) {
        this.tenantRepository = tenantRepository;
        this.llmProperties = llmProperties;
    }

    public LlmProvider resolve(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(Tenant::getPreferredLlmProvider)
                .filter(value -> value != null && !value.isBlank())
                .flatMap(LlmProvider::parse)
                .orElseGet(llmProperties::resolvedProvider);
    }
}
