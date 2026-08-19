package com.enterprisehub.gateway.webhook;

import java.util.UUID;

/**
 * What happened to one delivery. Three outcomes, all of them successes as
 * far as GitHub is concerned -- a rejected signature or unknown endpoint
 * throws {@link WebhookException} instead of being represented here, because
 * those are the only cases that should show up red in a repo's delivery log.
 */
public record WebhookIngestResult(Outcome outcome, UUID executionId) {

    public enum Outcome {
        /** A new execution was queued. */
        QUEUED,
        /** This delivery id was already handled -- executionId is the ORIGINAL run, not a new one. */
        DUPLICATE,
        /** Well-formed and genuinely from GitHub, but nothing to do (a ping, or an action this endpoint doesn't act on). */
        IGNORED
    }

    public static WebhookIngestResult queued(UUID executionId) {
        return new WebhookIngestResult(Outcome.QUEUED, executionId);
    }

    public static WebhookIngestResult duplicate(UUID executionId) {
        return new WebhookIngestResult(Outcome.DUPLICATE, executionId);
    }

    public static WebhookIngestResult ignored() {
        return new WebhookIngestResult(Outcome.IGNORED, null);
    }
}
