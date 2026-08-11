package com.enterprisehub.gateway.agent.input;

import com.enterprisehub.gateway.agent.AgentException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputSourceResolverRegistryTest {

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void resolve_dispatchesToTheMatchingResolverBySourceType() {
        InputSourceResolverRegistry registry = new InputSourceResolverRegistry(List.of(new ManualTextInputResolver()));

        String resolved = registry.resolve("MANUAL_TEXT", tenantId, Map.of("text", "Ticket: fix the bug"));

        assertThat(resolved).isEqualTo("Ticket: fix the bug");
    }

    @Test
    void resolve_unknownSourceType_throwsInternalServerError() {
        InputSourceResolverRegistry registry = new InputSourceResolverRegistry(List.of(new ManualTextInputResolver()));

        assertThatThrownBy(() -> registry.resolve("JIRA_TICKET", tenantId, Map.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("JIRA_TICKET")
                .satisfies(e -> assertThat(((AgentException) e).getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Test
    void resolve_multipleResolvers_eachOnlyHandlesItsOwnSourceType() {
        InputSourceResolver first = fakeResolver("TYPE_A", "resolved-a");
        InputSourceResolver second = fakeResolver("TYPE_B", "resolved-b");
        InputSourceResolverRegistry registry = new InputSourceResolverRegistry(List.of(first, second));

        assertThat(registry.resolve("TYPE_A", tenantId, Map.of())).isEqualTo("resolved-a");
        assertThat(registry.resolve("TYPE_B", tenantId, Map.of())).isEqualTo("resolved-b");
    }

    private InputSourceResolver fakeResolver(String sourceType, String resolvedText) {
        return new InputSourceResolver() {
            @Override
            public String sourceType() {
                return sourceType;
            }

            @Override
            public String resolve(UUID tenantId, Map<String, String> parameters) {
                return resolvedText;
            }
        };
    }
}
