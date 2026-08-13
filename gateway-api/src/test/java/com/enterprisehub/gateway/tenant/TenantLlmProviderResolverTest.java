package com.enterprisehub.gateway.tenant;

import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.entity.Tenant;
import com.enterprisehub.gateway.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantLlmProviderResolverTest {

    private TenantRepository tenantRepository;
    private TenantLlmProviderResolver resolver;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        LlmProperties llmProperties = new LlmProperties("ANTHROPIC", "claude-3-5-sonnet-20240620", null, null, null, null, 500_000, 100);
        resolver = new TenantLlmProviderResolver(tenantRepository, llmProperties);
    }

    private Tenant tenantWithPreference(String preferredLlmProvider) {
        return tenantWithPreference(preferredLlmProvider, null);
    }

    private Tenant tenantWithPreference(String preferredLlmProvider, String preferredModelName) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Acme");
        tenant.setSlug("acme");
        tenant.setPreferredLlmProvider(preferredLlmProvider);
        tenant.setPreferredModelName(preferredModelName);
        return tenant;
    }

    @Test
    void resolve_tenantHasNoPreference_fallsBackToServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenantWithPreference(null)));

        assertThat(resolver.resolve(tenantId)).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void resolve_tenantHasBlankPreference_fallsBackToServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenantWithPreference("  ")));

        assertThat(resolver.resolve(tenantId)).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void resolve_tenantPrefersLocal_returnsLocalNotServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenantWithPreference("LOCAL")));

        assertThat(resolver.resolve(tenantId)).isEqualTo(LlmProvider.LOCAL);
    }

    @Test
    void resolve_tenantPreferenceIsUnparseable_fallsBackToServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenantWithPreference("NOT_A_REAL_PROVIDER")));

        assertThat(resolver.resolve(tenantId)).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void resolve_unknownTenant_fallsBackToServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(tenantId)).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void resolveModelName_tenantHasNoPreference_fallsBackToServerDefaultForThatProvider() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenantWithPreference(null, null)));

        assertThat(resolver.resolveModelName(tenantId, LlmProvider.ANTHROPIC)).isEqualTo("claude-3-5-sonnet-20240620");
    }

    @Test
    void resolveModelName_tenantHasBlankPreference_fallsBackToServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenantWithPreference(null, "  ")));

        assertThat(resolver.resolveModelName(tenantId, LlmProvider.ANTHROPIC)).isEqualTo("claude-3-5-sonnet-20240620");
    }

    @Test
    void resolveModelName_tenantHasPreference_returnsItInsteadOfServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenantWithPreference(null, "claude-opus-4-1-20250805")));

        assertThat(resolver.resolveModelName(tenantId, LlmProvider.ANTHROPIC)).isEqualTo("claude-opus-4-1-20250805");
    }

    @Test
    void resolveModelName_unknownTenant_fallsBackToServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThat(resolver.resolveModelName(tenantId, LlmProvider.ANTHROPIC)).isEqualTo("claude-3-5-sonnet-20240620");
    }

    @Test
    void resolveMaxTokens_tenantHasNoOverride_fallsBackToServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenantWithPreference(null)));

        assertThat(resolver.resolveMaxTokens(tenantId)).isEqualTo(500_000);
    }

    @Test
    void resolveMaxTokens_tenantHasOverride_returnsItInsteadOfServerDefault() {
        Tenant tenant = tenantWithPreference(null);
        tenant.setMaxTokensPerExecution(50_000);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThat(resolver.resolveMaxTokens(tenantId)).isEqualTo(50_000);
    }

    @Test
    void resolveMaxTokens_unknownTenant_fallsBackToServerDefault() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThat(resolver.resolveMaxTokens(tenantId)).isEqualTo(500_000);
    }
}
