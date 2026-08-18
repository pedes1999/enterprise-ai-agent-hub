package com.enterprisehub.gateway.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The misconfiguration guarded against here is quiet and expensive: if
 * stale-after is not comfortably longer than the heartbeat interval, the
 * reaper fails healthy executions in the gap between two of their own beats,
 * killing real paid-for agent work with an error message blaming a restart
 * that never happened. Failing at startup is much better than discovering
 * that from a confused user.
 */
class JobWorkerPropertiesTest {

    @Test
    void staleAfterComfortablyLongerThanHeartbeat_isAccepted() {
        JobWorkerProperties properties = new JobWorkerProperties(30_000, 60_000, Duration.ofMinutes(5));

        assertThat(properties.staleAfter()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void staleAfterShorterThanHeartbeat_rejectedAtStartup() {
        assertThatThrownBy(() -> new JobWorkerProperties(60_000, 60_000, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be longer than heartbeat-interval-ms");
    }

    @Test
    void staleAfterExactlyTheHeartbeatInterval_rejected() {
        // Equal is still wrong -- a beat that lands a millisecond late is
        // already outside the window.
        assertThatThrownBy(() -> new JobWorkerProperties(30_000, 60_000, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be longer than heartbeat-interval-ms");
    }

    @Test
    void nonPositiveIntervals_rejected() {
        assertThatThrownBy(() -> new JobWorkerProperties(0, 60_000, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new JobWorkerProperties(30_000, 0, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }
}
