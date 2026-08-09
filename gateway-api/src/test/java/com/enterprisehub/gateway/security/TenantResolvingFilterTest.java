package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TenantResolvingFilterTest {

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void whenPlatformPrincipalAuthenticated_setsTenantContextDuringChain_thenClearsAfter() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal("user-1", "tenant-xyz", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        // Assert the tenant is visible from INSIDE the chain, not just after.
        doAnswer(invocation -> {
            assertThat(TenantContext.get()).isEqualTo("tenant-xyz");
            return null;
        }).when(chain).doFilter(request, response);

        new TenantResolvingFilter().doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(TenantContext.get()).isNull(); // cleared in finally
    }

    @Test
    void whenNoAuthentication_tenantContextStaysNull() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        doAnswer(invocation -> {
            assertThat(TenantContext.get()).isNull();
            return null;
        }).when(chain).doFilter(request, response);

        new TenantResolvingFilter().doFilter(request, response, chain);

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void whenChainThrows_tenantContextStillClearedAfterward() {
        PlatformPrincipal principal = new PlatformPrincipal("user-1", "tenant-xyz", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        try {
            doThrow(new RuntimeException("downstream failure")).when(chain).doFilter(request, response);
            new TenantResolvingFilter().doFilter(request, response, chain);
        } catch (Exception ignored) {
            // expected to propagate
        }

        assertThat(TenantContext.get()).isNull();
    }
}
