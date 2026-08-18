package com.enterprisehub.gateway.agent;

import com.enterprisehub.dto.ToolExecutionRecord;
import com.enterprisehub.gateway.config.ExecutionStreamProperties;
import com.enterprisehub.gateway.entity.AgentExecution;
import com.enterprisehub.gateway.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backs GET /agents/executions/{id}/stream -- lets a caller watch a run
 * unfold instead of only seeing the tool-call trace once it's finished.
 *
 * DB-polled, deliberately, not fed by an in-process listener the running
 * job publishes to. The instance serving this stream may not be the
 * instance running the job (the same constraint V32's heartbeat and V33's
 * cancellation both had to design around), so an in-memory event bus would
 * silently stream nothing at all whenever the two happen to differ -- which
 * is most of the time under more than one replica. Every fact this streams
 * is already durably in Postgres: agent_executions.status for the run
 * itself, and tool_executions rows, which JpaToolExecutionListener writes
 * synchronously as each tool call completes.
 *
 * Polling runs on this class's own executor, NOT the caller's request
 * thread and NOT Spring's shared @Scheduled pool (AgentJobWorker occupies
 * that one for the entire duration of a run -- see ExecutionHeartbeatMonitor's
 * javadoc for the same reasoning). That means TenantContext must be set
 * explicitly on every tick and cleared in a finally: the poll thread is
 * long-lived and reused across streams and tenants, so a leaked context
 * would widen the next tick's RLS visibility to whoever ran last. This is
 * exactly the class of bug TenantAwareDataSource's javadoc warns about.
 */
@Service
public class ExecutionStreamService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStreamService.class);

    /** Terminal statuses -- reaching one of these completes the stream. Mirrors AgentExecutionService's own transitions. */
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    private final AgentExecutionService executionService;
    private final AgentExecutionResponseMapper responseMapper;
    private final ExecutionStreamProperties properties;
    private final ScheduledExecutorService scheduler;

    public ExecutionStreamService(AgentExecutionService executionService, AgentExecutionResponseMapper responseMapper,
                                   ExecutionStreamProperties properties) {
        this.executionService = executionService;
        this.responseMapper = responseMapper;
        this.properties = properties;
        this.scheduler = Executors.newScheduledThreadPool(properties.pollThreads(), runnable -> {
            Thread thread = new Thread(runnable, "execution-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /**
     * The tenant check happens HERE, on the request thread, so an unknown or
     * cross-tenant id fails as an ordinary 404 response body rather than as
     * an error event inside an already-committed 200 SSE stream (once the
     * first byte is written the status line can't be taken back).
     *
     * An execution that is ALREADY terminal still gets a stream: one status
     * event with the final state, then completion. Returning 409 instead
     * would force every caller to race the run's own ending -- open a stream
     * a moment too late and you'd get an error instead of the answer you
     * asked for.
     */
    public SseEmitter stream(UUID tenantId, UUID executionId) {
        AgentExecution execution = executionService.findForTenant(tenantId, executionId)
                .orElseThrow(() -> new AgentException(HttpStatus.NOT_FOUND, "No execution with id " + executionId));

        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        // Both start at "nothing sent yet", so the first tick always emits
        // the current status and every tool call that already ran -- a
        // caller attaching mid-run gets the backlog, then live updates,
        // rather than only whatever happens to change after it connected.
        AtomicReference<String> lastStatus = new AtomicReference<>();
        AtomicReference<Integer> lastToolCount = new AtomicReference<>(0);
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

        // First tick is delayed by one interval rather than firing at 0.
        // With no delay, a stream for an already-terminal execution can emit
        // AND complete before the container has finished setting up async
        // processing for this response -- the client then sees a truncated
        // chunked body ("EOF reached while reading") instead of a clean
        // close. Costs nothing perceptible: the page has already rendered
        // from the ordinary GETs by the time this attaches.
        long intervalMs = properties.pollIntervalMs();
        task.set(scheduler.scheduleWithFixedDelay(
                () -> poll(emitter, tenantId, executionId, lastStatus, lastToolCount, task),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS));

        // Whatever ends the stream -- the run finishing, the client
        // navigating away (onError, broken pipe), or the emitter's own
        // timeout -- must stop the polling task, or it keeps querying the
        // DB every tick forever for a connection nobody is reading.
        emitter.onCompletion(() -> cancel(task));
        emitter.onTimeout(() -> {
            cancel(task);
            emitter.complete();
        });
        emitter.onError(e -> cancel(task));

        log.debug("Opened execution stream for {} (initial status {})", executionId, execution.getStatus());
        return emitter;
    }

    private void poll(SseEmitter emitter, UUID tenantId, UUID executionId,
                       AtomicReference<String> lastStatus, AtomicReference<Integer> lastToolCount,
                       AtomicReference<ScheduledFuture<?>> task) {
        TenantContext.set(tenantId.toString());
        try {
            AgentExecution execution = executionService.findForTenant(tenantId, executionId).orElse(null);
            if (execution == null) {
                // Deleted mid-stream -- nothing left to report on.
                finish(emitter, task);
                return;
            }

            // Tool rows are append-only and returned oldest-first, so "how
            // many have I already sent" is enough to find the new ones --
            // no per-row id bookkeeping, and no risk of re-sending one the
            // client already rendered.
            List<ToolExecutionRecord> toolExecutions = executionService.getToolExecutions(tenantId, executionId);
            int alreadySent = lastToolCount.get();
            if (toolExecutions.size() > alreadySent) {
                for (ToolExecutionRecord record : toolExecutions.subList(alreadySent, toolExecutions.size())) {
                    emitter.send(SseEmitter.event().name("tool").data(record));
                }
                lastToolCount.set(toolExecutions.size());
            }

            // Status is re-sent only when it actually changes, so a long
            // RUNNING stretch costs one event, not one per tick.
            String status = execution.getStatus();
            if (!status.equals(lastStatus.get())) {
                emitter.send(SseEmitter.event().name("status").data(responseMapper.toResponse(execution)));
                lastStatus.set(status);
            }

            if (TERMINAL_STATUSES.contains(status)) {
                finish(emitter, task);
            }
        } catch (IOException e) {
            // The client went away (navigated off the page, closed the tab).
            // Routine, not an error worth logging loudly -- just stop.
            log.debug("Execution stream for {} closed by the client", executionId);
            cancel(task);
            emitter.completeWithError(e);
        } catch (RuntimeException e) {
            // A throw would otherwise kill this repeating task silently
            // (ScheduledExecutorService's contract for scheduleWithFixedDelay),
            // leaving the client connected to a stream that has quietly
            // stopped polling -- worse than closing it outright.
            log.warn("Execution stream for {} failed; closing it", executionId, e);
            cancel(task);
            emitter.completeWithError(e);
        } finally {
            TenantContext.clear();
        }
    }

    private void finish(SseEmitter emitter, AtomicReference<ScheduledFuture<?>> task) {
        cancel(task);
        emitter.complete();
    }

    /**
     * Never cancels with interruption: this task may be mid-DB-query, and
     * interrupting the connection out from under it just turns a clean stop
     * into a spurious error. The task ends after the current tick either way.
     */
    private void cancel(AtomicReference<ScheduledFuture<?>> task) {
        ScheduledFuture<?> scheduled = task.get();
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }
}
