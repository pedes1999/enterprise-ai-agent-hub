package com.enterprisehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for AgentJobWorker's poll loop and ExecutionHeartbeatMonitor's
 * liveness/reaping sweeps. Reuses the existing app.job-worker namespace
 * (enabled/poll-interval-ms were already there, read via @Value/
 * @ConditionalOnProperty) rather than inventing a second one for the two
 * halves of the same subsystem.
 *
 * The one constraint worth stating: staleAfter must be comfortably larger
 * than heartbeatIntervalMs, or a perfectly healthy job gets reaped in the
 * gap between two of its own beats. The defaults leave a wide margin (a
 * beat every 30s against a 5-minute staleness threshold, so a run has to
 * miss ~10 consecutive beats before anything touches it) because the cost
 * of the two mistakes is wildly asymmetric: reaping a live job kills real,
 * paid-for work mid-flight, while leaving an abandoned row a few extra
 * minutes costs nothing but a briefly-occupied concurrency slot.
 */
@ConfigurationProperties(prefix = "app.job-worker")
public record JobWorkerProperties(
        long heartbeatIntervalMs,
        long reapIntervalMs,
        Duration staleAfter) {

    public JobWorkerProperties {
        if (heartbeatIntervalMs <= 0 || reapIntervalMs <= 0) {
            throw new IllegalArgumentException("job-worker heartbeat/reap intervals must be positive");
        }
        if (staleAfter.toMillis() <= heartbeatIntervalMs) {
            throw new IllegalArgumentException(
                    "app.job-worker.stale-after (" + staleAfter + ") must be longer than heartbeat-interval-ms ("
                            + heartbeatIntervalMs + ") -- otherwise a healthy execution is reaped between its own beats");
        }
    }
}
