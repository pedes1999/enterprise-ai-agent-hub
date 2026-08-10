package com.enterprisehub.gateway.credential;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaCredentialResolverTest {

    @Test
    void resolve_gitCredentialPresent_mapsToGitTokenEnvVar() {
        ToolCredentialService service = mock(ToolCredentialService.class);
        UUID tenantId = UUID.randomUUID();
        when(service.decryptActiveValue(tenantId, "GIT")).thenReturn(Optional.of("ghp_secret"));

        Map<String, String> resolved = new JpaCredentialResolver(service).resolve(tenantId.toString(), "GIT");

        assertThat(resolved).containsExactly(Map.entry("GIT_TOKEN", "ghp_secret"));
    }

    @Test
    void resolve_noCredentialConfigured_returnsEmptyMap_notAnError() {
        ToolCredentialService service = mock(ToolCredentialService.class);
        UUID tenantId = UUID.randomUUID();
        when(service.decryptActiveValue(tenantId, "GIT")).thenReturn(Optional.empty());

        Map<String, String> resolved = new JpaCredentialResolver(service).resolve(tenantId.toString(), "GIT");

        assertThat(resolved).isEmpty();
    }

    @Test
    void resolve_unknownKind_stillMapsToASaneEnvVarName() {
        ToolCredentialService service = mock(ToolCredentialService.class);
        UUID tenantId = UUID.randomUUID();
        when(service.decryptActiveValue(tenantId, "SOMETHING_NEW")).thenReturn(Optional.of("value"));

        Map<String, String> resolved = new JpaCredentialResolver(service).resolve(tenantId.toString(), "SOMETHING_NEW");

        assertThat(resolved).containsExactly(Map.entry("SOMETHING_NEW_TOKEN", "value"));
    }
}
