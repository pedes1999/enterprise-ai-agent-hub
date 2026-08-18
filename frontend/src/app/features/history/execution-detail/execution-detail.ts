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

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.agentService.getExecution(id).subscribe({
      next: (exec) => {
        this.execution.set(exec);
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(extractErrorMessage(err, 'Failed to load this execution.'));
        this.loading.set(false);
      },
    });
    this.agentService.getToolExecutions(id).subscribe({
      next: (records) => this.toolExecutions.set(records),
      error: (err) => this.traceError.set(extractErrorMessage(err, 'Failed to load the tool-call trace.')),
    });
    this.agentService.getChildren(id).subscribe({
      next: (records) => this.children.set(records),
      error: (err) => this.childrenError.set(extractErrorMessage(err, 'Failed to load delegated executions.')),
    });
  }
}
