import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { AgentService } from '../../../core/services/agent.service';
import { ExecutionUsage } from '../../../core/models/agent.model';

export const USAGE_POLL_INTERVAL_MS = 15000;

@Component({
  selector: 'app-usage-indicator',
  templateUrl: './usage-indicator.html',
  styleUrl: './usage-indicator.css',
})
export class UsageIndicator implements OnInit, OnDestroy {
  private readonly agentService = inject(AgentService);
  private pollSubscription?: Subscription;

  readonly usage = signal<ExecutionUsage | null>(null);

  ngOnInit(): void {
    this.pollSubscription = timer(0, USAGE_POLL_INTERVAL_MS)
      .pipe(switchMap(() => this.agentService.getUsage()))
      .subscribe((usage) => this.usage.set(usage));
  }

  ngOnDestroy(): void {
    this.pollSubscription?.unsubscribe();
  }
}
