package com.enterprisehub.gateway.tenant;

/**
 * Carries the resolved tenant ID for the duration of a single request/thread.
 *
 * Populated by {@link com.enterprisehub.gateway.security.TenantResolvingFilter}
 * after authentication resolves who the caller is. Read by
 * {@link com.enterprisehub.gateway.tenant.TenantSessionAspect} to set the
 * Postgres session variable that RLS policies key off.
 *
 * MUST be cleared at the end of every request (see the filter's finally block) —
 * thread pools reuse threads, and a stale tenant ID leaking into the next
 * request on the same thread would be a cross-tenant data leak.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String get() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
