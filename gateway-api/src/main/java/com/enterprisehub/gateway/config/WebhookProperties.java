package com.enterprisehub.gateway.config;

/**
 * publicBaseUrl is the origin GitHub can actually reach this app on -- used
 * only to assemble the delivery URL returned by POST /webhook-endpoints, so
 * an admin can copy one value straight into GitHub's webhook settings
 * instead of hand-building the path.
 *
 * It is cosmetic, never trusted: nothing on the ingest path reads it, and a
 * wrong value produces an unusable URL to copy, not a security hole. It has
 * to be configured rather than derived from the incoming request precisely
 * because the request that creates an endpoint comes from the admin's
 * browser (localhost, an internal hostname) while deliveries arrive from
 * GitHub on a public address -- inferring one from the other would be
 * confidently wrong in every deployment behind a proxy.
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app.webhooks")
public record WebhookProperties(String publicBaseUrl) {
}
