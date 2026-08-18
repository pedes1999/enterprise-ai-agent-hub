import { Component, inject, OnInit, signal } from '@angular/core';
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
export class ExecutionDetail implements OnInit {
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

  private executionId = '';

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
