package com.enterprisehub.gateway.agent.input;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualTextInputResolverTest {

    private final ManualTextInputResolver resolver = new ManualTextInputResolver();
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void sourceType_isManualText() {
        assertThat(resolver.sourceType()).isEqualTo("MANUAL_TEXT");
    }

    @Test
    void resolve_returnsTheTextParameterVerbatim() {
        String resolved = resolver.resolve(tenantId, Map.of("text", "Ticket: fix the login bug"));

        assertThat(resolved).isEqualTo("Ticket: fix the login bug");
    }

    @Test
    void resolve_missingTextParameter_throws() {
        assertThatThrownBy(() -> resolver.resolve(tenantId, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void resolve_nullParameters_throws() {
        assertThatThrownBy(() -> resolver.resolve(tenantId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_blankTextParameter_throws() {
        assertThatThrownBy(() -> resolver.resolve(tenantId, Map.of("text", "   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
