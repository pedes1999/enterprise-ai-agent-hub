package com.enterprisehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for ExecutionStreamService (GET /agents/executions/{id}/stream).
 *
 * pollIntervalMs is deliberately tighter than AgentJobWorker's own 2s poll:
 * this one exists to make a run feel live to a human watching it, and a
 * tool call finishing is the event they're waiting on. It's a cheap
 * indexed read of two rows per tick, not a claim query with a row lock.
 *
 * timeout bounds how long a single connection can stay open. A stream ends
 * on its own when the run reaches a terminal status, so this only ever
 * fires for a run that outlives it (a long test-fixer execution) or a
 * client that connected and then stopped reading without closing. The
 * frontend treats a timeout as "reconnect", not "the run failed", so
 * erring shorter is safe -- the cost of a too-long timeout is a leaked
 * connection and its poll task, which is the more expensive mistake.
 */
@ConfigurationProperties(prefix = "app.execution-stream")
public record ExecutionStreamProperties(
        long pollIntervalMs,
        Duration timeout,
        int pollThreads) {

    public ExecutionStreamProperties {
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("app.execution-stream.poll-interval-ms must be positive");
        }
        if (pollThreads <= 0) {
            throw new IllegalArgumentException("app.execution-stream.poll-threads must be positive");
        }
    }
}
