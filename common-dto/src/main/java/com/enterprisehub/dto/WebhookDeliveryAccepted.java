package com.enterprisehub.dto;

/**
 * What the app did with one webhook delivery, echoed back into GitHub's
 * delivery log where a repo admin can read it.
 *
 * outcome is QUEUED, DUPLICATE, or IGNORED. On DUPLICATE, executionId is the
 * run the FIRST copy of this delivery created -- reporting it (rather than a
 * bare acknowledgement) is what makes a redelivery genuinely idempotent from
 * the caller's point of view: they learn where the work went, without a
 * second run being started or billed. executionId is null for IGNORED.
 */
public record WebhookDeliveryAccepted(
        String outcome,
        String executionId
) {
}
