package com.enterprisehub.gateway.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Bridges {@link TenantContext} (Java-side, per-thread) to Postgres RLS
 * (DB-side, per-connection) by running:
 *
 *     SELECT set_config('app.current_tenant_id', ?, true)
 *
 * as the first statement of every @Transactional method. The `true` argument
 * makes it transaction-local (equivalent to SET LOCAL) — it's automatically
 * unset when the transaction ends, so a pooled connection handed to the next
 * request starts with no tenant context by default (fails closed, not open).
 *
 * Ordering matters here: Spring's TransactionInterceptor must run FIRST so a
 * transaction (and therefore a bound connection) already exists when this
 * aspect fires. @Order(0) here vs Spring's transaction advice, which defaults
 * to a lower precedence value and therefore wraps around this one — this
 * aspect runs *inside* the transaction boundary, not before it starts.
 */
@Aspect
@Component
@Order(0)
public class TenantSessionAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional) || " +
            "@within(org.springframework.transaction.annotation.Transactional)")
    public Object setTenantSessionVariable(ProceedingJoinPoint joinPoint) throws Throwable {
        String tenantId = TenantContext.get();

        if (tenantId != null) {
            entityManager.createNativeQuery("SELECT set_config('app.current_tenant_id', :tenantId, true)")
                    .setParameter("tenantId", tenantId)
                    .getSingleResult();
        }
        // If tenantId is null (e.g. an unauthenticated/system-level operation like
        // tenant provisioning itself), current_tenant_id stays unset and RLS
        // policies deny all rows by default — fail closed, never open.

        return joinPoint.proceed();
    }
}
