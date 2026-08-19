package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.config.RateLimitProperties;
import com.enterprisehub.gateway.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed-window request cap on the endpoints reachable without a credential.
 *
 * The route that motivates it is POST /webhooks/github/{endpointId}. It is
 * {@code permitAll} by necessity -- GitHub has no account here -- and it does
 * a database lookup AND an HMAC over the request body BEFORE it can decide a
 * caller is bogus. That ordering is required for security (the signature is
 * the only credential, so it has to be checked) but it means every junk
 * request costs real CPU and a connection from the pool. Without a ceiling,
 * anyone who learns the URL has a cheap amplifier. /auth/** is covered on the
 * same reasoning, where the cost is password hashing and new tenant rows.
 *
 * Runs as the FIRST filter in the chain, ahead of JwtAuthFilter, so a
 * rejected request never reaches signature verification, a DB query, or the
 * handler. A limiter placed after the work it is meant to prevent would be
 * decorative.
 *
 * <h2>What this deliberately is not</h2>
 *
 * The counters live in this instance's memory, so N replicas admit N times
 * the configured rate. That is a real limitation and is stated rather than
 * hidden: making it exact needs shared state (Redis), which this project does
 * not run, and adding an entire datastore to make an abuse ceiling precise
 * would be a poor trade. The ceiling exists to stop a flood from being free,
 * and being off by the replica count does not change that. A deployment that
 * needs an exact global limit should turn this off
 * ({@code app.rate-limit.enabled: false}) and let its ingress do it, which is
 * where a correct distributed limiter belongs anyway.
 *
 * Fixed window rather than a sliding one or a token bucket, for the same
 * reason: a fixed window is a counter and a timestamp, it is obviously
 * correct on inspection, and its known weakness (up to 2x the rate across a
 * window boundary) is irrelevant at the scale this protects against.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /**
     * Set by every reverse proxy and ingress in front of an app like this.
     * Without honouring it, every request behind a proxy shares the proxy's
     * address as one key and the whole deployment shares a single bucket --
     * the limiter would then throttle everyone the moment one caller was
     * noisy. The first entry is the original client.
     *
     * It is caller-supplied and therefore spoofable, which is exactly why the
     * bucket count is bounded: a caller forging a fresh value per request
     * gets a fresh bucket each time and evicts its way through the map rather
     * than growing it without limit.
     */
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Access-ordered and size-capped, so the least recently seen client is
     * evicted once the map is full. Synchronized on itself at every touch --
     * LinkedHashMap is not thread-safe and this runs on every request thread.
     * The critical section is a map lookup, not I/O.
     */
    private final Map<String, Window> windows;

    public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        int cap = Math.max(1, properties.maxTrackedClients());
        this.windows = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
                return size() > cap;
            }
        };
    }

    /** One client's request count and the instant its window opened. */
    private static final class Window {
        private final AtomicInteger count = new AtomicInteger();
        private volatile long startedAtMillis;

        Window(long now) {
            this.startedAtMillis = now;
        }
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!properties.enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return properties.pathPrefixes().stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String client = clientKey(request);
        long now = System.currentTimeMillis();
        long windowMillis = properties.window().toMillis();

        int used;
        synchronized (windows) {
            Window window = windows.computeIfAbsent(client, key -> new Window(now));
            if (now - window.startedAtMillis >= windowMillis) {
                // Window elapsed -- reset rather than decay. See the class
                // javadoc on why a fixed window is the right shape here.
                window.startedAtMillis = now;
                window.count.set(0);
            }
            used = window.count.incrementAndGet();
        }

        if (used > properties.requests()) {
            // No client identifier in the log line: on this route the caller
            // is anonymous by definition, so the address is the only thing to
            // record, and recording it per rejected request is how a flood
            // becomes a second denial-of-service against the log volume.
            log.warn("Rate limit exceeded on {} -- {} requests in the current window (limit {})",
                    request.getRequestURI(), used, properties.requests());
            reject(response, windowMillis);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Rejections carry Retry-After so a well-behaved client backs off for the
     * right length of time instead of guessing -- and GitHub's webhook
     * redelivery is exactly such a client.
     */
    private void reject(HttpServletResponse response, long windowMillis) throws IOException {
        long retryAfterSeconds = Math.max(1, windowMillis / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                new ApiError("Too many requests -- retry in " + retryAfterSeconds + "s."));
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
