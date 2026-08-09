package com.enterprisehub.gateway.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TenantSessionAspectTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private TenantSessionAspect newAspect(EntityManager entityManager) {
        TenantSessionAspect aspect = new TenantSessionAspect();
        try {
            Field field = TenantSessionAspect.class.getDeclaredField("entityManager");
            field.setAccessible(true);
            field.set(aspect, entityManager);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return aspect;
    }

    @Test
    void whenTenantContextSet_setsPostgresSessionVariableBeforeProceeding() throws Throwable {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("result");

        TenantContext.set("tenant-abc");
        Object result = newAspect(entityManager).setTenantSessionVariable(joinPoint);

        assertThat(result).isEqualTo("result");
        verify(entityManager).createNativeQuery(contains_set_config());
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(query).setParameter(eq("tenantId"), valueCaptor.capture());
        assertThat(valueCaptor.getValue()).isEqualTo("tenant-abc");
        verify(query).getSingleResult();
        verify(joinPoint).proceed();
    }

    @Test
    void whenTenantContextNull_skipsSetConfig_butStillProceeds() throws Throwable {
        EntityManager entityManager = mock(EntityManager.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("result");

        Object result = newAspect(entityManager).setTenantSessionVariable(joinPoint);

        assertThat(result).isEqualTo("result");
        verifyNoInteractions(entityManager);
        verify(joinPoint).proceed();
    }

    @Test
    void whenJoinPointThrows_exceptionPropagates() throws Throwable {
        EntityManager entityManager = mock(EntityManager.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        RuntimeException boom = new RuntimeException("boom");
        when(joinPoint.proceed()).thenThrow(boom);

        try {
            newAspect(entityManager).setTenantSessionVariable(joinPoint);
        } catch (RuntimeException e) {
            assertThat(e).isSameAs(boom);
            return;
        }
        throw new AssertionError("expected exception to propagate");
    }

    private static String contains_set_config() {
        return "SELECT set_config('app.current_tenant_id', :tenantId, true)";
    }
}
