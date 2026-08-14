package com.enterprisehub.runtime.sandbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SandboxSessionTest {

    private final SandboxSpec sessionSpec = new SandboxSpec(
            "tenant-1", "exec-1", Map.of("GIT_TOKEN", "ghp_secret"), Duration.ofMinutes(10), 65536);

    @Test
    void create_firstCall_provisionsUsingTheSessionsOwnSpec_notTheCallers() {
        SandboxClient delegate = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(delegate.create(sessionSpec)).thenReturn(handle);
        SandboxSession session = new SandboxSession(delegate, sessionSpec);

        SandboxSpec unrelatedCallerSpec = new SandboxSpec("tenant-1", "exec-1", Map.of(), Duration.ofSeconds(30), 1024);
        SandboxHandle result = session.create(unrelatedCallerSpec);

        assertThat(result).isEqualTo(handle);
        verify(delegate).create(sessionSpec);
        verify(delegate, never()).create(unrelatedCallerSpec);
    }

    @Test
    void create_secondCall_returnsCachedHandle_neverCreatesTwice() {
        SandboxClient delegate = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(delegate.create(any())).thenReturn(handle);
        SandboxSession session = new SandboxSession(delegate, sessionSpec);

        SandboxHandle first = session.create(sessionSpec);
        SandboxHandle second = session.create(new SandboxSpec("tenant-1", "exec-1", Map.of(), Duration.ofMinutes(1), 512));

        assertThat(first).isEqualTo(second);
        verify(delegate, times(1)).create(any());
    }

    @Test
    void destroy_isANoOp_untilEndSessionIsCalled() {
        SandboxClient delegate = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(delegate.create(any())).thenReturn(handle);
        SandboxSession session = new SandboxSession(delegate, sessionSpec);
        session.create(sessionSpec);

        session.destroy(handle);

        verify(delegate, never()).destroy(any());
    }

    @Test
    void endSession_destroysTheRealHandle() {
        SandboxClient delegate = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(delegate.create(any())).thenReturn(handle);
        SandboxSession session = new SandboxSession(delegate, sessionSpec);
        session.create(sessionSpec);

        session.endSession();

        verify(delegate).destroy(handle);
    }

    @Test
    void endSession_neverCreatedASandbox_doesNothing() {
        SandboxClient delegate = mock(SandboxClient.class);
        SandboxSession session = new SandboxSession(delegate, sessionSpec);

        session.endSession();

        verifyNoInteractions(delegate);
    }

    @Test
    void endSession_calledTwice_secondCallIsANoOp() {
        SandboxClient delegate = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(delegate.create(any())).thenReturn(handle);
        SandboxSession session = new SandboxSession(delegate, sessionSpec);
        session.create(sessionSpec);

        session.endSession();
        session.endSession();

        verify(delegate, times(1)).destroy(handle);
    }

    /**
     * ToolCallingChatEngine can now run several tool calls from one round
     * concurrently (see its javadoc) -- two sandboxed tools could race into
     * create() at the same instant. Forces that race deliberately: both
     * threads start blocked on the same latch, delegate.create() itself
     * sleeps briefly so a broken (unsynchronized) implementation would have
     * a real window to let both threads see handle == null and both call
     * delegate.create(). Asserts exactly one real provisioning call
     * happened and both callers got back the identical handle.
     */
    @Test
    void create_calledConcurrentlyByTwoThreads_onlyProvisionsOnce() throws InterruptedException {
        SandboxClient delegate = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        when(delegate.create(sessionSpec)).thenAnswer(invocation -> {
            Thread.sleep(50);
            return handle;
        });
        SandboxSession session = new SandboxSession(delegate, sessionSpec);

        CountDownLatch startLatch = new CountDownLatch(1);
        List<SandboxHandle> results = new CopyOnWriteArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Runnable callCreate = () -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results.add(session.create(sessionSpec));
            };
            executor.submit(callCreate);
            executor.submit(callCreate);
            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(delegate, times(1)).create(sessionSpec);
        assertThat(results).hasSize(2).containsOnly(handle);
    }

    @Test
    void runCommandWriteFileReadFile_delegateStraightThrough() {
        SandboxClient delegate = mock(SandboxClient.class);
        SandboxHandle handle = new SandboxHandle("s1");
        CommandResult commandResult = new CommandResult(0, "ok", "", false, Duration.ZERO);
        when(delegate.runCommand(handle, "ls", Duration.ofSeconds(5))).thenReturn(commandResult);
        when(delegate.readFile(handle, "/tmp/workspace/repo/a.txt")).thenReturn("content".getBytes());
        SandboxSession session = new SandboxSession(delegate, sessionSpec);

        assertThat(session.runCommand(handle, "ls", Duration.ofSeconds(5))).isEqualTo(commandResult);
        session.writeFile(handle, "/tmp/workspace/repo/a.txt", "content".getBytes());
        assertThat(session.readFile(handle, "/tmp/workspace/repo/a.txt")).isEqualTo("content".getBytes());
        verify(delegate).writeFile(handle, "/tmp/workspace/repo/a.txt", "content".getBytes());
    }
}
