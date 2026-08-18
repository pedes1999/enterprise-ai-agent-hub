package com.enterprisehub.gateway.agent;

import com.enterprisehub.gateway.config.JobWorkerProperties;
import com.enterprisehub.gateway.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps RUNNING executions honest: stamps a liveness signal on the ones this
 * instance is actually running, and fails the ones no instance is stamping
 * any more. See V32__agent_execution_heartbeat.sql for the lockout this
 * exists to fix (abandoned RUNNING rows permanently consuming a tenant's
 * concurrency cap).
 *
 * Runs on its own single-thread scheduler rather than Spring's @Scheduled,
 * and that is load-bearing, not a style choice. Spring's default
 * TaskScheduler pool is ONE thread, and AgentJobWorker.pollAndProcessOne()
 * occupies it for the entire duration of an agent run (fixedDelay, and a run
 * can last minutes -- see that class's javadoc). A @Scheduled heartbeat
 * would therefore be starved for exactly as long as there was something to
 * heartbeat, stamping nothing while a job ran and then reaping it the
 * moment it finished. The dedicated executor here is never blocked by a
 * running job.
 *
 * Both tasks run under the worker sentinel tenant for the same reason
 * AgentJobWorker's claim step does -- they operate across every tenant's
 * rows, not one tenant's (see V5's RLS carve-out and TenantContext's
 * SYSTEM_WORKER_TENANT_ID). Each task clears the context in a finally block,
 * the same discipline used everywhere else the sentinel is set: this
 * executor's thread is long-lived and reused across ticks, so a leaked
 * context would silently widen the next tick's visibility.
 *
 * Disabled alongside the worker itself (app.job-worker.enabled=false, set in
 * application-test.yml) -- an integration test asserting on agent_executions
 * rows should no more race a background reaper than a background poller.
 */
@Component
@ConditionalOnProperty(prefix = "app.job-worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionHeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(ExecutionHeartbeatMonitor.class);

    private final AgentExecutionService executionService;
    private final JobWorkerProperties properties;

    /**
     * Execution ids this instance is running right now. A set, not a single
     * id, because AgentJobWorker running one job at a time is a property of
     * its current fixedDelay scheduling, not a guarantee -- its own javadoc
     * calls out raising the pool size as the intended way to run several at
     * once, and this should keep working when that happens.
     */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private ScheduledExecutorService scheduler;

    public ExecutionHeartbeatMonitor(AgentExecutionService executionService, JobWorkerProperties properties) {
        this.executionService = executionService;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "execution-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long beatMs = properties.heartbeatIntervalMs();
        scheduler.scheduleWithFixedDelay(this::beat, beatMs, beatMs, TimeUnit.MILLISECONDS);
        // Offset from the heartbeat so a sweep and a stamp don't contend on
        // the same rows on every single tick.
        scheduler.scheduleWithFixedDelay(this::reap, beatMs / 2, properties.reapIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Called by AgentJobWorker around a claimed run -- see its runClaimedJob(). */
    void track(UUID executionId) {
        inFlight.add(executionId);
    }

    void untrack(UUID executionId) {
        inFlight.remove(executionId);
    }

    private void beat() {
        runAsWorker("heartbeat", () -> executionService.heartbeat(Set.copyOf(inFlight)));
    }

    private void reap() {
        runAsWorker("reap", () -> {
            int reaped = executionService.reapStaleRunning(properties.staleAfter());
            if (reaped > 0) {
                // Deliberately WARN, not INFO: reaching here means an app
                // instance died mid-run, which is a real operational event
                // worth noticing, not routine housekeeping.
                log.warn("Reaped {} abandoned RUNNING execution(s) with no heartbeat for over {}",
                        reaped, properties.staleAfter());
            }
        });
    }

    /**
     * A throw here would kill the whole repeating task (that's
     * ScheduledExecutorService's contract for scheduleWithFixedDelay), which
     * would silently disable heartbeating or reaping for the rest of the
     * process's life -- so a transient failure (a DB blip, a connection
     * timeout) is logged and swallowed, leaving the next tick to try again.
     */
    private void runAsWorker(String task, Runnable action) {
        TenantContext.set(TenantContext.SYSTEM_WORKER_TENANT_ID);
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Execution {} sweep failed; will retry on the next tick", task, e);
        } finally {
            TenantContext.clear();
        }
    }
}
