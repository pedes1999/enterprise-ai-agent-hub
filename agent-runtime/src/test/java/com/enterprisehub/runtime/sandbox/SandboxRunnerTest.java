package com.enterprisehub.runtime.sandbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SandboxRunnerTest {

    private final SandboxSpec spec = new SandboxSpec("tenant-1", "exec-1", Map.of(), Duration.ofMinutes(1), 1024);

    @Test
    void withSandbox_createsThenDestroys_onSuccess() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(client.create(spec)).thenReturn(handle);

        String result = new SandboxRunner(client).withSandbox(spec, h -> "work result for " + h.id());

        assertThat(result).isEqualTo("work result for s1");
        verify(client).destroy(handle);
    }

    @Test
    void withSandbox_destroysEvenWhenWorkThrows() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(client.create(spec)).thenReturn(handle);

        assertThatThrownBy(() -> new SandboxRunner(client).withSandbox(spec, h -> {
            throw new RuntimeException("work failed");
        })).isInstanceOf(RuntimeException.class).hasMessage("work failed");

        verify(client).destroy(handle);
    }

    @Test
    void withSandbox_destroyFailure_doesNotMaskTheOriginalWorkException() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(client.create(spec)).thenReturn(handle);
        doThrow(new RuntimeException("destroy also failed")).when(client).destroy(handle);

        assertThatThrownBy(() -> new SandboxRunner(client).withSandbox(spec, h -> {
            throw new IllegalStateException("the real failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the real failure");
    }

    @Test
    void withSandbox_destroyFailure_whenWorkSucceeds_doesNotPropagate() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(client.create(spec)).thenReturn(handle);
        doThrow(new RuntimeException("destroy failed")).when(client).destroy(handle);

        String result = new SandboxRunner(client).withSandbox(spec, h -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void withSandbox_createFailure_neverCallsDestroy() {
        SandboxClient client = mock(SandboxClient.class);
        when(client.create(spec)).thenThrow(new SandboxException("cannot create"));

        assertThatThrownBy(() -> new SandboxRunner(client).withSandbox(spec, h -> "unreachable"))
                .isInstanceOf(SandboxException.class);

        verify(client, never()).destroy(any());
    }
}
