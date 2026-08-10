package com.enterprisehub.runtime.sandbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxSpecTest {

    @Test
    void validSpec_constructsFine() {
        SandboxSpec spec = new SandboxSpec("t1", "e1", Map.of("K", "V"), Duration.ofMinutes(1), 1024);
        assertThat(spec.tenantId()).isEqualTo("t1");
        assertThat(spec.credentials()).containsEntry("K", "V");
    }

    @Test
    void nullCredentials_defaultsToEmptyMap_notNull() {
        SandboxSpec spec = new SandboxSpec("t1", "e1", null, Duration.ofMinutes(1), 1024);
        assertThat(spec.credentials()).isNotNull().isEmpty();
    }

    @Test
    void credentialsMap_isDefensivelyCopied_immutable() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("K", "V");
        SandboxSpec spec = new SandboxSpec("t1", "e1", mutable, Duration.ofMinutes(1), 1024);

        mutable.put("K2", "V2"); // mutate the original after construction
        assertThat(spec.credentials()).doesNotContainKey("K2");
        assertThatThrownBy(() -> spec.credentials().put("K3", "V3")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blankTenantId_rejected() {
        assertThatThrownBy(() -> new SandboxSpec(" ", "e1", Map.of(), Duration.ofMinutes(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullTenantId_rejected() {
        assertThatThrownBy(() -> new SandboxSpec(null, "e1", Map.of(), Duration.ofMinutes(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankExecutionId_rejected() {
        assertThatThrownBy(() -> new SandboxSpec("t1", " ", Map.of(), Duration.ofMinutes(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroOrNegativeMaxLifetime_rejected() {
        assertThatThrownBy(() -> new SandboxSpec("t1", "e1", Map.of(), Duration.ZERO, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxSpec("t1", "e1", Map.of(), Duration.ofSeconds(-1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullMaxLifetime_rejected() {
        assertThatThrownBy(() -> new SandboxSpec("t1", "e1", Map.of(), null, 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroOrNegativeMaxOutputBytes_rejected() {
        assertThatThrownBy(() -> new SandboxSpec("t1", "e1", Map.of(), Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxSpec("t1", "e1", Map.of(), Duration.ofMinutes(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
