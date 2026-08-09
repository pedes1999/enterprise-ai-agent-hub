package com.enterprisehub.gateway.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void get_beforeAnySet_isNull() {
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void setThenGet_returnsSetValue() {
        TenantContext.set("tenant-123");
        assertThat(TenantContext.get()).isEqualTo("tenant-123");
    }

    @Test
    void clear_removesValue() {
        TenantContext.set("tenant-123");
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void set_overwritesPreviousValue() {
        TenantContext.set("tenant-1");
        TenantContext.set("tenant-2");
        assertThat(TenantContext.get()).isEqualTo("tenant-2");
    }

    @Test
    void isolated_perThread() throws InterruptedException {
        TenantContext.set("main-thread-tenant");

        String[] otherThreadValue = new String[1];
        Thread thread = new Thread(() -> otherThreadValue[0] = TenantContext.get());
        thread.start();
        thread.join();

        assertThat(otherThreadValue[0]).isNull();
        assertThat(TenantContext.get()).isEqualTo("main-thread-tenant");
    }
}
