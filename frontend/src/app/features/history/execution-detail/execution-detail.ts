import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AgentService } from '../../../core/services/agent.service';
import { AgentExecutionStatusResponse, ToolExecutionRecord } from '../../../core/models/agent.model';
import { LocalDateTimePipe } from '../../../shared/pipes/local-date-time.pipe';
import { extractErrorMessage } from '../../../shared/http/error-message';

@Component({
  selector: 'app-execution-detail',
  imports: [RouterLink, LocalDateTimePipe],
  templateUrl: './execution-detail.html',
  styleUrl: './execution-detail.css',
})
export class ExecutionDetail implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly agentService = inject(AgentService);

  readonly execution = signal<AgentExecutionStatusResponse | null>(null);
  readonly toolExecutions = signal<ToolExecutionRecord[]>([]);
  readonly children = signal<AgentExecutionStatusResponse[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);
  readonly traceError = signal<string | null>(null);
  readonly childrenError = signal<string | null>(null);
  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);
  /** True while an SSE stream is attached -- drives the "Live" indicator. */
  readonly streaming = signal(false);

  private executionId = '';
  private streamAbort?: AbortController;

  ngOnInit(): void {
    this.executionId = this.route.snapshot.paramMap.get('id')!;
    this.loadExecution();
    this.agentService.getToolExecutions(this.executionId).subscribe({
      next: (records) => this.toolExecutions.set(records),
      error: (err) => this.traceError.set(extractErrorMessage(err, 'Failed to load the tool-call trace.')),
    });
    this.agentService.getChildren(this.executionId).subscribe({
      next: (records) => this.children.set(records),
      error: (err) => this.childrenError.set(extractErrorMessage(err, 'Failed to load delegated executions.')),
    });
    void this.attachStream();
  }

  ngOnDestroy(): void {
    // Without this the connection (and the poll task behind it on the
    // server) outlives the page the user already navigated away from.
    this.streamAbort?.abort();
  }

  /**
   * The stream replays everything so far before going live, so it's safe to
   * attach regardless of how far along the run already is -- including one
   * that's already finished, which just yields its final status and closes.
   *
   * Deliberately silent on failure: this is a live-updates enhancement on
   * top of the data the three requests above already fetched. If the stream
   * can't be established the page still shows a correct (if static) view,
   * which is a better outcome than an alarming error banner over content
   * that loaded perfectly well.
   */
  private async attachStream(): Promise<void> {
    this.streamAbort = new AbortController();
    this.streaming.set(true);
    // Accumulated separately, then published wholesale, because the stream
    // replays the whole trace from the beginning: appending its events onto
    // the list ngOnInit's getToolExecutions() call already populated would
    // render every pre-existing tool call twice. Replacing instead makes
    // the stream authoritative the moment it delivers anything, while the
    // initial fetch still covers the case where it never connects at all.
    const streamed: ToolExecutionRecord[] = [];
    try {
      for await (const event of this.agentService.streamExecution(this.executionId, this.streamAbort.signal)) {
        if (event.event === 'status') {
          this.execution.set(event.data as AgentExecutionStatusResponse);
        } else {
          streamed.push(event.data as ToolExecutionRecord);
          this.toolExecutions.set([...streamed]);
        }
      }
    } catch {
      // Aborted on destroy, or the connection dropped -- see above.
    } finally {
      this.streaming.set(false);
    }
  }

  /** True while cancelling is a meaningful action -- a terminal execution has nothing left to stop. */
  canCancel(status: string): boolean {
    return status === 'QUEUED' || status === 'RUNNING';
  }

  cancel(): void {
    this.cancelling.set(true);
    this.cancelError.set(null);
    this.agentService.cancel(this.executionId).subscribe({
      next: () => {
        this.cancelling.set(false);
        // Re-fetch rather than assume: a QUEUED cancel is already CANCELLED
        // by the time this returns, but a RUNNING one only flagged the
        // request -- re-fetching reflects whichever is actually true instead
        // of the UI guessing.
        this.loadExecution();
      },
      error: (err) => {
        this.cancelling.set(false);
        this.cancelError.set(extractErrorMessage(err, 'Failed to cancel this execution.'));
      },
    });
  }

  private loadExecution(): void {
    this.agentService.getExecution(this.executionId).subscribe({
      next: (exec) => {
        this.execution.set(exec);
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(extractErrorMessage(err, 'Failed to load this execution.'));
        this.loading.set(false);
      },
    });
  }
}
