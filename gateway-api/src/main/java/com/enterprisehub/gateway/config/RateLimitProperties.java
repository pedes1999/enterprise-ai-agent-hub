package com.enterprisehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Limits for the endpoints that are reachable without a credential.
 *
 * Only the unauthenticated surface is covered, deliberately. An
 * authenticated caller is already bounded by things that cost real money and
 * are enforced server-side -- the per-tenant concurrency cap and the monthly
 * budget -- and is identifiable and revocable if they misbehave. An
 * anonymous caller is neither, so the ceiling has to be the request rate
 * itself.
 *
 * @param enabled        off switch, for a deployment fronted by a gateway that
 *                       already rate-limits (an ingress, Cloudflare, an API
 *                       gateway). Two limiters in series just make the effective
 *                       limit harder to reason about.
 * @param pathPrefixes   which paths are limited. Defaults to the two
 *                       unauthenticated routes: webhook ingest and auth.
 * @param requests       requests allowed per client within the window.
 * @param window         the window those requests are counted over.
 * @param maxTrackedClients ceiling on how many client keys are held at once.
 *                       Load-bearing rather than tidiness: without it, a caller
 *                       rotating source addresses turns the limiter itself into
 *                       an unbounded map and the memory-exhaustion vector it was
 *                       added to prevent.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        List<String> pathPrefixes,
        int requests,
        Duration window,
        int maxTrackedClients) {
}
