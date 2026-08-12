import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, switchMap, takeWhile, timer } from 'rxjs';
import { AgentService } from '../../core/services/agent.service';
import { AgentExecutionStatusResponse, AgentDefinitionDetail, ExecutionUsage } from '../../core/models/agent.model';

export const POLL_INTERVAL_MS = 2000;

interface InputParamRow {
  key: string;
  value: string;
}

type TriggerErrorKind = 'rate-limit' | 'required-inputs' | 'generic' | null;

@Component({
  selector: 'app-trigger',
  imports: [FormsModule, RouterLink],
  templateUrl: './trigger.html',
  styleUrl: './trigger.css',
})
export class Trigger implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly agentService = inject(AgentService);
  private pollSubscription?: Subscription;

  readonly slug = this.route.snapshot.paramMap.get('slug')!;
  readonly definition = signal<AgentDefinitionDetail | null>(null);
  readonly usage = signal<ExecutionUsage | null>(null);

  prompt = '';
  repositoryUrl = '';
  paramRows: InputParamRow[] = [{ key: '', value: '' }];

  readonly submitting = signal(false);
  readonly errorKind = signal<TriggerErrorKind>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly execution = signal<AgentExecutionStatusResponse | null>(null);

  ngOnInit(): void {
    this.agentService.getDefinition(this.slug).subscribe((detail) => this.definition.set(detail));
    this.refreshUsage();
  }

  ngOnDestroy(): void {
    this.pollSubscription?.unsubscribe();
  }

  private refreshUsage(): void {
    this.agentService.getUsage().subscribe((usage) => this.usage.set(usage));
  }

  addParamRow(): void {
    this.paramRows.push({ key: '', value: '' });
  }

  removeParamRow(index: number): void {
    this.paramRows.splice(index, 1);
  }

  submit(): void {
    this.submitting.set(true);
    this.errorKind.set(null);
    this.errorMessage.set(null);
    this.execution.set(null);

    const inputParameters: Record<string, string> = {};
    for (const row of this.paramRows) {
      if (row.key.trim()) {
        inputParameters[row.key.trim()] = row.value;
      }
    }

    this.agentService
      .execute({
        prompt: this.prompt || null,
        agentSlug: this.slug,
        repositoryUrl: this.repositoryUrl || null,
        inputParameters: Object.keys(inputParameters).length > 0 ? inputParameters : null,
      })
      .subscribe({
        next: (accepted) => {
          this.submitting.set(false);
          this.refreshUsage();
          this.startPolling(accepted.executionId);
        },
        error: (err) => {
          this.submitting.set(false);
          if (err.status === 429) {
            this.errorKind.set('rate-limit');
            this.errorMessage.set(err.error?.message ?? 'Execution limit reached for this organization.');
          } else if (err.status === 400 && (err.error?.message ?? '').startsWith('Missing required input')) {
            this.errorKind.set('required-inputs');
            this.errorMessage.set(err.error.message);
          } else {
            this.errorKind.set('generic');
            this.errorMessage.set(err.error?.message ?? 'Failed to trigger the agent.');
          }
        },
      });
  }

  private startPolling(executionId: string): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = timer(0, POLL_INTERVAL_MS)
      .pipe(
        switchMap(() => this.agentService.getExecution(executionId)),
        takeWhile((exec) => exec.status === 'QUEUED' || exec.status === 'RUNNING', true),
      )
      .subscribe((exec) => this.execution.set(exec));
  }
}
