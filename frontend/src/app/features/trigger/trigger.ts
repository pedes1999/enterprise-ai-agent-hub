import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, switchMap, takeWhile, timer } from 'rxjs';
import { AgentService } from '../../core/services/agent.service';
import {
  AgentExecutionStatusResponse,
  AgentDefinitionDetail,
  AgentTokenUsageStats,
  ExecutionUsage,
} from '../../core/models/agent.model';
import { extractErrorMessage } from '../../shared/http/error-message';

export const POLL_INTERVAL_MS = 2000;

/** The two fixed-vocabulary requirement keys the backend validates against (see V10__agent_definition_required_inputs.sql) get dedicated labels; anything else is a generic inputParameters key. */
const FIELD_LABELS: Record<string, string> = {
  prompt: 'Prompt',
  repositoryUrl: 'Repository URL',
};

function labelFor(key: string): string {
  return FIELD_LABELS[key] ?? key.charAt(0).toUpperCase() + key.slice(1);
}

type TriggerErrorKind = 'rate-limit' | 'required-inputs' | 'generic' | null;

@Component({
  selector: 'app-trigger',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './trigger.html',
  styleUrl: './trigger.css',
})
export class Trigger implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly agentService = inject(AgentService);
  private pollSubscription?: Subscription;

  readonly slug = this.route.snapshot.paramMap.get('slug')!;
  readonly definition = signal<AgentDefinitionDetail | null>(null);
  readonly definitionLoading = signal(true);
  readonly definitionError = signal<string | null>(null);
  readonly usage = signal<ExecutionUsage | null>(null);
  readonly tokenUsageStats = signal<AgentTokenUsageStats | null>(null);

  /** One form field per entry in this agent's own requiredInputs (from the DB) -- never a fixed set shown for every agent regardless of relevance. */
  readonly fields = computed(() =>
    (this.definition()?.requiredInputs ?? []).map((key) => ({ key, label: labelFor(key) })),
  );

  /** Branch is never a required input (git_clone treats it as optional) -- shown alongside Repository URL whenever that field is, not gated by requiredInputs. */
  readonly showBranchField = computed(() => this.fields().some((f) => f.key === 'repositoryUrl'));

  fieldValues: Record<string, string> = {};
  branchValue = '';
  /** '' means "use this tenant's default token budget" -- same shape as branchValue's '' -> null. */
  maxTokensValue = '';
  readonly maxTokensError = signal<string | null>(null);

  readonly submitting = signal(false);
  readonly errorKind = signal<TriggerErrorKind>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly confirmationMessage = signal<string | null>(null);
  readonly execution = signal<AgentExecutionStatusResponse | null>(null);
  readonly pollError = signal<string | null>(null);

  ngOnInit(): void {
    this.agentService.getDefinition(this.slug).subscribe({
      next: (detail) => {
        this.definition.set(detail);
        this.definitionLoading.set(false);
        for (const key of detail.requiredInputs) {
          this.fieldValues[key] = this.fieldValues[key] ?? '';
        }
      },
      error: (err) => {
        this.definitionError.set(extractErrorMessage(err, 'Failed to load this agent.'));
        this.definitionLoading.set(false);
      },
    });
    this.refreshUsage();
    // Best-effort -- a fresh agent with no runs yet, or the request simply
    // failing, should never block the trigger form itself from working.
    this.agentService.getTokenUsageStats(this.slug).subscribe({
      next: (stats) => this.tokenUsageStats.set(stats),
      error: () => {},
    });
  }

  ngOnDestroy(): void {
    this.pollSubscription?.unsubscribe();
  }

  private refreshUsage(): void {
    this.agentService.getUsage().subscribe({ next: (usage) => this.usage.set(usage), error: () => {} });
  }

  submit(): void {
    this.maxTokensError.set(null);
    const maxTokensText = this.maxTokensValue.trim();
    if (maxTokensText && (!/^\d+$/.test(maxTokensText) || Number(maxTokensText) <= 0)) {
      this.maxTokensError.set('Max tokens must be a positive whole number.');
      return;
    }

    this.submitting.set(true);
    this.errorKind.set(null);
    this.errorMessage.set(null);
    this.confirmationMessage.set(null);
    this.pollError.set(null);
    this.execution.set(null);
    this.pollSubscription?.unsubscribe();

    const inputParameters: Record<string, string> = {};
    for (const { key } of this.fields()) {
      if (key !== 'prompt' && key !== 'repositoryUrl') {
        inputParameters[key] = this.fieldValues[key] ?? '';
      }
    }

    this.agentService
      .execute({
        prompt: this.fieldValues['prompt'] || null,
        agentSlug: this.slug,
        repositoryUrl: this.fieldValues['repositoryUrl'] || null,
        repositoryBranch: this.branchValue || null,
        inputParameters: Object.keys(inputParameters).length > 0 ? inputParameters : null,
        maxTokens: maxTokensText ? Number(maxTokensText) : null,
      })
      .subscribe({
        next: (accepted) => {
          this.submitting.set(false);
          this.confirmationMessage.set(`Execution ${accepted.executionId} queued.`);
          this.refreshUsage();
          this.startPolling(accepted.executionId);
        },
        error: (err) => {
          this.submitting.set(false);
          if (err.status === 429) {
            this.errorKind.set('rate-limit');
            this.errorMessage.set(extractErrorMessage(err, 'Execution limit reached for this organization.'));
          } else if (err.status === 400 && (extractErrorMessage(err, '')).startsWith('Missing required input')) {
            this.errorKind.set('required-inputs');
            this.errorMessage.set(err.error.message);
          } else {
            this.errorKind.set('generic');
            this.errorMessage.set(extractErrorMessage(err, 'Failed to trigger the agent.'));
          }
        },
      });
  }

  private startPolling(executionId: string): void {
    this.pollSubscription = timer(0, POLL_INTERVAL_MS)
      .pipe(
        switchMap(() => this.agentService.getExecution(executionId)),
        takeWhile((exec) => exec.status === 'QUEUED' || exec.status === 'RUNNING', true),
      )
      .subscribe({
        next: (exec) => this.execution.set(exec),
        error: (err) => this.pollError.set(extractErrorMessage(err, 'Lost connection while checking execution status.')),
      });
  }
}
