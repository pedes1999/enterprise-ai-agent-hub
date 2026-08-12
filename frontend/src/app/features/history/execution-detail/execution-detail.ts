import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AgentService } from '../../../core/services/agent.service';
import { AgentExecutionStatusResponse, ToolExecutionRecord } from '../../../core/models/agent.model';
import { LocalDateTimePipe } from '../../../shared/pipes/local-date-time.pipe';

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
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);
  readonly traceError = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.agentService.getExecution(id).subscribe({
      next: (exec) => {
        this.execution.set(exec);
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(err.error?.message ?? 'Failed to load this execution.');
        this.loading.set(false);
      },
    });
    this.agentService.getToolExecutions(id).subscribe({
      next: (records) => this.toolExecutions.set(records),
      error: (err) => this.traceError.set(err.error?.message ?? 'Failed to load the tool-call trace.'),
    });
  }
}
