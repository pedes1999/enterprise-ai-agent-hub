package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private static final List<String> GUARDED = List.of("/webhooks/", "/auth/");

    private RateLimitFilter filter(int requests, Duration window, boolean enabled, int maxClients) {
        return new RateLimitFilter(
                new RateLimitProperties(enabled, GUARDED, requests, window, maxClients), new ObjectMapper());
    }

    private MockHttpServletRequest request(String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setRemoteAddr(ip);
        return request;
    }

    /** Drives the filter the way the container does, honouring shouldNotFilter. */
    private MockHttpServletResponse pass(RateLimitFilter filter, MockHttpServletRequest request, FilterChain chain)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void allowsTrafficUpToTheLimit() throws Exception {
        RateLimitFilter filter = filter(3, Duration.ofMinutes(1), true, 100);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 3; i++) {
            assertThat(pass(filter, request("/webhooks/github/abc", "10.0.0.1"), chain).getStatus()).isEqualTo(200);
        }
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsPastTheLimitWithoutRunningTheRestOfTheChain() throws Exception {
        RateLimitFilter filter = filter(2, Duration.ofMinutes(1), true, 100);
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 2; i++) {
            pass(filter, request("/webhooks/github/abc", "10.0.0.1"), chain);
        }

        MockHttpServletResponse response = pass(filter, request("/webhooks/github/abc", "10.0.0.1"), chain);

        assertThat(response.getStatus()).isEqualTo(429);
        // The whole point: the request must not reach signature verification
        // or the database. A limiter that ran after the work it prevents
        // would be decorative.
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        // Retry-After so GitHub's redelivery backs off for the right length
        // of time rather than guessing.
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString()).contains("Too many requests");
    }

    @Test
    void countsEachClientSeparately() throws Exception {
        RateLimitFilter filter = filter(1, Duration.ofMinutes(1), true, 100);
        FilterChain chain = mock(FilterChain.class);
        pass(filter, request("/webhooks/github/abc", "10.0.0.1"), chain);

        // A second caller must not inherit the first one's exhausted budget.
        assertThat(pass(filter, request("/webhooks/github/abc", "10.0.0.2"), chain).getStatus()).isEqualTo(200);
    }

    @Test
    void treatsTheOriginalClientBehindAProxyAsTheSubject() throws Exception {
        RateLimitFilter filter = filter(1, Duration.ofMinutes(1), true, 100);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest first = request("/webhooks/github/abc", "172.16.0.1");
        first.addHeader("X-Forwarded-For", "203.0.113.7, 172.16.0.1");
        pass(filter, first, chain);

        // Same proxy, different origin client: without honouring the header
        // every caller behind an ingress would share one bucket, so a single
        // noisy client would throttle everybody.
        MockHttpServletRequest second = request("/webhooks/github/abc", "172.16.0.1");
        second.addHeader("X-Forwarded-For", "203.0.113.9, 172.16.0.1");

        assertThat(pass(filter, second, chain).getStatus()).isEqualTo(200);
    }

    @Test
    void startsAFreshAllowanceOnceTheWindowElapses() throws Exception {
        RateLimitFilter filter = filter(1, Duration.ofMillis(50), true, 100);
        FilterChain chain = mock(FilterChain.class);
        pass(filter, request("/webhooks/github/abc", "10.0.0.1"), chain);
        assertThat(pass(filter, request("/webhooks/github/abc", "10.0.0.1"), chain).getStatus()).isEqualTo(429);

        Thread.sleep(80);

        assertThat(pass(filter, request("/webhooks/github/abc", "10.0.0.1"), chain).getStatus()).isEqualTo(200);
    }

    @Test
    void leavesAuthenticatedRoutesAlone() throws Exception {
        RateLimitFilter filter = filter(1, Duration.ofMinutes(1), true, 100);
        FilterChain chain = mock(FilterChain.class);

        // Authenticated callers are bounded by the concurrency cap and the
        // monthly budget, and are revocable -- rate limiting them too would
        // throttle legitimate agent traffic for no added protection.
        for (int i = 0; i < 5; i++) {
            assertThat(pass(filter, request("/agents/execute", "10.0.0.1"), chain).getStatus()).isEqualTo(200);
        }
        verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void guardsTheAuthRoutesToo() throws Exception {
        RateLimitFilter filter = filter(1, Duration.ofMinutes(1), true, 100);
        FilterChain chain = mock(FilterChain.class);
        pass(filter, request("/auth/login", "10.0.0.1"), chain);

        // Password hashing is real work done before a bad credential can be
        // rejected -- the same shape of problem as the webhook route.
        assertThat(pass(filter, request("/auth/login", "10.0.0.1"), chain).getStatus()).isEqualTo(429);
    }

    @Test
    void doesNothingWhenDisabled() throws Exception {
        // For a deployment whose ingress already rate-limits: two limiters in
        // series only make the effective limit harder to reason about.
        RateLimitFilter filter = filter(1, Duration.ofMinutes(1), false, 100);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            assertThat(pass(filter, request("/webhooks/github/abc", "10.0.0.1"), chain).getStatus()).isEqualTo(200);
        }
        verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void boundsItsOwnMemoryWhenAClientRotatesAddresses() throws Exception {
        // X-Forwarded-For is caller-supplied and spoofable. Without the cap
        // the tracking map would grow per forged address and the limiter
        // would become the exhaustion vector it was added to prevent.
        RateLimitFilter filter = filter(1, Duration.ofMinutes(1), true, 2);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 500; i++) {
            pass(filter, request("/webhooks/github/abc", "10.0.0." + i), chain);
        }

        // The oldest entries were evicted, so an early address is treated as
        // new again rather than being remembered forever.
        assertThat(pass(filter, request("/webhooks/github/abc", "10.0.0.0"), chain).getStatus()).isEqualTo(200);
        verify(chain, never()).doFilter(null, null);
    }
}
