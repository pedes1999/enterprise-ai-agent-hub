import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AgentService } from '../../../core/services/agent.service';
import { AgentExecutionStatusResponse, ToolExecutionRecord } from '../../../core/models/agent.model';

@Component({
  selector: 'app-execution-detail',
  imports: [RouterLink],
  templateUrl: './execution-detail.html',
  styleUrl: './execution-detail.css',
})
export class ExecutionDetail implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly agentService = inject(AgentService);

  readonly execution = signal<AgentExecutionStatusResponse | null>(null);
  readonly toolExecutions = signal<ToolExecutionRecord[]>([]);
  readonly loading = signal(true);

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.agentService.getExecution(id).subscribe((exec) => {
      this.execution.set(exec);
      this.loading.set(false);
    });
    this.agentService.getToolExecutions(id).subscribe((records) => this.toolExecutions.set(records));
  }
}
